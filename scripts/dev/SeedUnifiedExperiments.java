import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.shared.lab.LabWorkbook;
import com.jsd.aird.tpl.application.UnifiedLabTemplateCatalog;
import com.jsd.aird.tpl.infrastructure.XlsxStructureParser;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Random;
import java.util.UUID;

/** Deterministic synthetic fixtures for non-production environments only. */
public class SeedUnifiedExperiments {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !"--replace-generated".equals(args[3])))
            throw new IllegalArgumentException("Usage: <jdbc-url> <username> <records-per-template> [--replace-generated]; password in SEED_DB_PASSWORD");
        boolean replace = args.length == 4;
        int count = Integer.parseInt(args[2]);
        if (count < 1 || count > 1000) throw new IllegalArgumentException("records-per-template must be 1..1000");
        var json = new ObjectMapper();
        var catalog = new UnifiedLabTemplateCatalog(json);
        var parser = new XlsxStructureParser(json);
        try (var db = DriverManager.getConnection(args[0], args[1], System.getenv().getOrDefault("SEED_DB_PASSWORD", ""))) {
            db.setAutoCommit(false);
            try {
                for (String kind : new String[]{"test", "synthesis"}) {
                    String code = kind.equals("test") ? UnifiedLabTemplateCatalog.TEST : UnifiedLabTemplateCatalog.SYNTHESIS;
                    var pack = catalog.compile(code, parser.parseInstance(SeedUnifiedExperiments.class.getResourceAsStream("/templates/unified/" + kind + ".xlsx"))).payload();
                    try (var templates = db.prepareStatement("select t.organization_id,t.created_by,v.id,v.editor_snapshot_hash from tpl.template t join tpl.template_version v on v.id=t.current_published_version_id where t.template_code=? and v.status='PUBLISHED'")) {
                        templates.setString(1, code);
                        try (var rs = templates.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("Published unified template missing: " + code);
                            UUID org = rs.getObject(1, UUID.class), actor = rs.getObject(2, UUID.class), version = rs.getObject(3, UUID.class);
                            String hash = rs.getString(4);
                            createMaterials(db, actor);
                            for (int n = 1; n <= count; n++) {
                                String number = String.format(kind.equals("test") ? "WB-C-%04d" : "WB-R-%04d", n);
                                UUID existingExperiment = null, existingVersion = null;
                                try (var exists = db.prepareStatement("select e.id,e.current_version_id,v.template_version_id,v.edit_model_jsonb->>'dataNature' from rnd.experiment e join rnd.experiment_version v on v.id=e.current_version_id where e.organization_id=? and e.experiment_no=?")) {
                                    exists.setObject(1, org); exists.setString(2, number);
                                    try (var found = exists.executeQuery()) { if (found.next()) {
                                        if (!"SYNTHETIC".equals(found.getString(4)) || !version.equals(found.getObject(3, UUID.class)))
                                            throw new IllegalStateException("Existing record is not a matching generated fixture: " + number);
                                        if (!replace) continue;
                                        existingExperiment=found.getObject(1, UUID.class); existingVersion=found.getObject(2, UUID.class);
                                    } }
                                }
                                String title = kind.equals("test") ? "水性涂层配方性能研究 " + n : "水性丙烯酸树脂合成研究 " + n;
                                var snapshot = pack.path("snapshot").deepCopy();
                                var sheet = LabWorkbook.sheet(snapshot);
                                LabWorkbook.cell(sheet, 0, 1).put("v", title);
                                LabWorkbook.cell(sheet, 1, 1).put("v", "研发人员");
                                LabWorkbook.cell(sheet, 1, 3).put("v", LocalDate.of(2025, 1, 1).plusDays(n).toString());
                                LabWorkbook.cell(sheet, 1, 5).put("v", 23);
                                LabWorkbook.cell(sheet, 1, 7).put("v", 0.55);
                                int sampleRow = kind.equals("test") ? 6 : 8, materialRow = kind.equals("test") ? 51 : 9;
                                String[] materials = kind.equals("test")
                                        ? new String[]{"WB-ACR-001", "WB-ACR-002", "WB-ADD-001"}
                                        : new String[]{"WB-MON-001", "WB-MON-002", "WB-INIT-001", "WB-WAT-001"};
                                for (int i = 0; i < materials.length; i++) LabWorkbook.cell(sheet, materialRow + i, 1).put("v", materials[i]);
                                Random random = new Random(43017L + n * 193L + (kind.equals("test") ? 0 : 100000));
                                for (int c = 2; c < 8; c++) {
                                    double a = round(kind.equals("test") ? 110 + random.nextDouble() * 250 : 70 + random.nextDouble() * 100, 1);
                                    double b = round(kind.equals("test") ? 35 + random.nextDouble() * 130 : 20 + random.nextDouble() * 50, 1);
                                    double additive = round(kind.equals("test") ? 3 + random.nextDouble() * 24 : 0.8 + random.nextDouble() * 3.2, 1);
                                    double water = kind.equals("test") ? 0 : round(150 + random.nextDouble() * 200, 1);
                                    double fraction = a / (a + b + additive);
                                    double temp = round(72 + random.nextDouble() * 18, 1);
                                    LabWorkbook.cell(sheet, sampleRow, c).put("v", number + "-" + (c - 1));
                                    LabWorkbook.cell(sheet, materialRow, c).put("v", a);
                                    LabWorkbook.cell(sheet, materialRow + 1, c).put("v", b);
                                    LabWorkbook.cell(sheet, materialRow + 2, c).put("v", additive);
                                    if (!kind.equals("test")) LabWorkbook.cell(sheet, materialRow + 3, c).put("v", water);
                                    if (kind.equals("test")) {
                                        set(sheet, "condition.substrate", c, "PC"); set(sheet, "curing.mode", c, "UV光固化");
                                        set(sheet, "condition.application", c, "线棒涂布"); set(sheet, "curing.equipment", c, "汞灯");
                                        set(sheet, "curing.time", c, "30 s"); set(sheet, "curing.wavelength", c, 365);
                                        set(sheet, "curing.intensity", c, 165); set(sheet, "curing.energy", c, 800);
                                        set(sheet, "resin.materialCode", c, "WB-ACR-001"); set(sheet, "resin.composition", c, "水性丙烯酸树脂复配");
                                        set(sheet, "resin.appearance", c, "乳白色均匀液体"); setPercent(sheet, "resin.solids", c, round(40 + 6*fraction + random.nextGaussian()*0.4, 1));
                                        set(sheet, "resin.viscosity", c, round(240 + 180*fraction + random.nextGaussian()*7, 1));
                                        set(sheet, "resin.refractiveIndex", c, round(1.42 + 0.025*fraction, 4));
                                        set(sheet, "resin.density", c, round(1.02 + 0.03*fraction, 3));
                                        set(sheet, "resin.fineness", c, round(5 + random.nextDouble()*4, 1));
                                        set(sheet, "resin.particleSize", c, round(85 + random.nextDouble()*30, 1));
                                        set(sheet, "resin.ph", c, round(7.2 + random.nextDouble()*0.8, 1));
                                        set(sheet, "resin.surfaceTension", c, round(31 + random.nextDouble()*4, 1));
                                        set(sheet, "resin.acidValue", c, round(8 + random.nextDouble()*5, 1));
                                        set(sheet, "resin.heatStability", c, "无明显变化"); set(sheet, "resin.separation", c, "无分层");
                                        set(sheet, "resin.compatibility", c, "相容"); set(sheet, "resin.solubility", c, "均匀分散");
                                        set(sheet, "plan.detail", c, "调整树脂及助剂用量"); set(sheet, "sample.experimentNo", c, number+"-"+(c-1));
                                        set(sheet, "coating.appearance", c, "均匀无颗粒"); setPercent(sheet, "coating.solids", c, round(35+7*fraction+random.nextGaussian()*0.3,1));
                                        set(sheet, "coating.viscosity", c, round(160+130*fraction+random.nextGaussian()*5,1));
                                        set(sheet, "film.curing", c, "表面干燥"); set(sheet, "film.appearance", c, "平整透明");
                                        set(sheet, "film.thickness", c, round(18 + random.nextDouble() * 12, 1));
                                        double gloss=round(89 + 5*fraction - 0.11*additive + random.nextGaussian()*0.7,1);
                                        double contact=round(69 + 28*fraction + 0.08*additive + random.nextGaussian()*1.2,1);
                                        set(sheet, "optical.gloss20", c, round(gloss-5,1)); set(sheet, "optical.gloss60", c, gloss); set(sheet, "optical.gloss85", c, round(Math.min(99,gloss+2),1));
                                        setPercent(sheet, "optical.haze", c, round(1.2+random.nextDouble()*1.6,1)); setPercent(sheet, "optical.transmittance", c, round(89+random.nextDouble()*3,1));
                                        set(sheet, "hardness.1000g", c, fraction>0.72?"H":"HB"); set(sheet, "hardness.700g", c, fraction>0.72?"2H":"H");
                                        set(sheet, "hardness.500g", c, fraction>0.72?"2H":"H");
                                        set(sheet, "adhesion.pc", c, fraction > 0.77 ? "4B" : "5B"); set(sheet, "adhesion.afterBoiling.pc", c, fraction > 0.77 ? "3B" : "4B");
                                        set(sheet, "optical.deltaE", c, round(0.3+random.nextDouble()*0.6,2));
                                        set(sheet, "abrasion.steel300g1cm", c, Math.round(120+180*fraction+random.nextGaussian()*8));
                                        set(sheet, "abrasion.steel500g1cm", c, Math.round(90+140*fraction+random.nextGaussian()*7));
                                        set(sheet, "abrasion.rca", c, Math.round(55+100*fraction+random.nextGaussian()*5));
                                        set(sheet, "contact.water", c, contact); set(sheet, "contact.hexadecane", c, round(38+15*fraction+random.nextGaussian(),1));
                                        set(sheet, "contact.diiodomethane", c, round(51+16*fraction+random.nextGaussian(),1));
                                        set(sheet, "boiling.appearance", c, "无明显变化"); set(sheet, "boiling.contactWater", c, round(contact-3-random.nextDouble()*2,1));
                                        set(sheet, "chemical.rain", c, "无明显变化"); set(sheet, "chemical.solvent", c, "轻微失光");
                                        set(sheet, "aging.deltaE", c, round(0.5+random.nextDouble()*0.8,2));
                                        set(sheet, "mechanical.flexibility", c, "3 mm，无裂纹"); set(sheet, "mechanical.curvature", c, "平整不翘曲");
                                        set(sheet, "friction.static", c, round(0.3+random.nextDouble()*0.12,2)); set(sheet, "friction.dynamic", c, round(0.22+random.nextDouble()*0.08,2));
                                        set(sheet, "scratch.nail", c, "无明显划痕");
                                    } else {
                                        set(sheet, "plan.content", c, "调整投料用量与反应温度"); set(sheet, "process.temperature", c, temp);
                                        set(sheet, "process.summary", c, "搅拌混合，升温反应后冷却出料"); set(sheet, "process.vacuumTemperature", c, round(temp-8,1));
                                        set(sheet, "process.additionTemperature", c, round(temp-5,1)); set(sheet, "process.time", c, round(2.5 + random.nextDouble() * 1.5, 1)+" h");
                                        double total=a+b+additive+water; double product=round(total*(0.94+random.nextDouble()*0.03),1);
                                        double theoreticalSolids=(a+b+additive)/total*100;
                                        double actualSolids=round((a+b+additive)*0.965/product*100,1);
                                        set(sheet, "design.solidMass", c, round(a+b+additive,1));
                                        setPercent(sheet, "design.theoreticalHalfSolids", c, round(theoreticalSolids,1));
                                        setPercent(sheet, "design.actualHalfSolids", c, round(theoreticalSolids-0.3+random.nextGaussian()*0.2,1));
                                        set(sheet, "design.theoreticalHydroxyl", c, round(35+12*fraction,1));
                                        setPercent(sheet, "design.conversion", c, round(94+random.nextDouble()*4,1));
                                        set(sheet, "design.theoreticalMass", c, round(total,1)); set(sheet, "design.productMass", c, product);
                                        setPercent(sheet, "design.recovery", c, round(product/total*100,1));
                                        setPercent(sheet, "design.initiator", c, round(additive/total*100,2));
                                        set(sheet, "appearance.beforePurification", c, "浅黄色液体"); set(sheet, "appearance.afterPurification", c, "透明液体");
                                        set(sheet, "product.viscosity", c, round(95 + 150 * fraction + 120 * theoreticalSolids / 100 + 0.6 * temp + 3 * additive + random.nextGaussian() * 5, 1));
                                        setPercent(sheet, "product.solids", c, actualSolids);
                                        set(sheet, "product.refractiveIndex", c, round(1.42+0.025*fraction,4));
                                        // A water-based product cannot simultaneously contain only
                                        // ~0.2% water and ~30–50% solids.  Keep the residual
                                        // non-water volatile fraction small in this example series.
                                        setPercent(sheet, "product.water", c, round(100-actualSolids-random.nextDouble()*0.1,2));
                                        set(sheet, "appearance.afterCooling", c, "均匀透明液体");
                                    }
                                }
                                var prepared = LabWorkbook.prepare(json, snapshot, pack.path("snapshot"), number);
                                ObjectNode model = LabWorkbook.extract(json, prepared, json.createObjectNode(), number);
                                model.put("title", title).put("dataNature", "SYNTHETIC").put("documentFormat", "XLSX");
                                model.set("documentSnapshot", prepared); model.set("blankDocument", pack.path("snapshot"));
                                if (existingExperiment != null) {
                                    try (var s = db.prepareStatement("update rnd.experiment set title=?,updated_at=now(),revision=revision+1 where id=? and organization_id=?")) {
                                        s.setString(1,title); s.setObject(2,existingExperiment); s.setObject(3,org); s.executeUpdate();
                                    }
                                    try (var s = db.prepareStatement("update rnd.experiment_version set edit_model_jsonb=?::jsonb where id=? and organization_id=?")) {
                                        s.setString(1,json.writeValueAsString(model)); s.setObject(2,existingVersion); s.setObject(3,org); s.executeUpdate();
                                    }
                                    continue;
                                }
                                UUID experiment = UUID.randomUUID(), revision = UUID.randomUUID();
                                try (var s = db.prepareStatement("insert into rnd.experiment(id,organization_id,experiment_no,title,source_type,status,classification_status,owner_name,experiment_date,created_by,updated_by) values (?,?,?,?,'TEMPLATE','COMPLETED','CLASSIFIED','研发人员',?,?,?)")) {
                                    s.setObject(1, experiment); s.setObject(2, org); s.setString(3, number); s.setString(4, title);
                                    s.setObject(5, LocalDate.of(2025, 1, 1).plusDays(n)); s.setObject(6, actor); s.setObject(7, actor); s.executeUpdate();
                                }
                                try (var s = db.prepareStatement("insert into rnd.experiment_version(id,organization_id,experiment_id,version_no,status,template_version_id,template_snapshot_hash,template_snapshot_jsonb,edit_model_jsonb,created_by,published_at) values (?,?,?,1,'COMPLETED',?,?,?::jsonb,?::jsonb,?,now())")) {
                                    s.setObject(1, revision); s.setObject(2, org); s.setObject(3, experiment); s.setObject(4, version); s.setString(5, hash);
                                    s.setString(6, json.writeValueAsString(pack.path("snapshot"))); s.setString(7, json.writeValueAsString(model)); s.setObject(8, actor); s.executeUpdate();
                                }
                                try (var s = db.prepareStatement("update rnd.experiment set current_version_id=? where id=?")) { s.setObject(1, revision); s.setObject(2, experiment); s.executeUpdate(); }
                            }
                            if (rs.next()) throw new IllegalStateException("Specify an organization when multiple unified templates exist");
                        }
                    }
                }
                db.commit();
                System.out.println("Unified experiment fixtures created or already present: " + count + " per template.");
            } catch (Exception error) { db.rollback(); throw error; }
        }
    }
    private static double round(double value, int places) { double scale = Math.pow(10, places); return Math.round(value * scale) / scale; }
    private static void set(ObjectNode sheet, String field, int column, Object value) {
        int[] at = LabWorkbook.findField(sheet, field); if (at == null) throw new IllegalStateException("Missing field " + field);
        var cell = LabWorkbook.cell(sheet, at[0], column);
        if (value instanceof Number number) cell.put("v", number.doubleValue()); else cell.put("v", String.valueOf(value));
    }
    private static void setPercent(ObjectNode sheet, String field, int column, double percent) {
        int[] at = LabWorkbook.findField(sheet, field); if (at == null) throw new IllegalStateException("Missing field " + field);
        LabWorkbook.cell(sheet, at[0], column).put("v", java.math.BigDecimal.valueOf(percent).movePointLeft(2));
    }
    private static void createMaterials(java.sql.Connection db, UUID actor) throws Exception {
        String[][] values = {{"WB-ACR-001", "水性丙烯酸树脂 A", "RESIN"}, {"WB-ACR-002", "水性丙烯酸树脂 B", "RESIN"}, {"WB-ADD-001", "水性涂层助剂", "ADDITIVE"},
                {"WB-MON-001", "丙烯酸酯混合单体", "MONOMER"}, {"WB-MON-002", "功能性丙烯酸酯单体", "MONOMER"},
                {"WB-INIT-001", "水性聚合引发剂", "ADDITIVE"}, {"WB-WAT-001", "去离子水", "SOLVENT"}};
        try (var s = db.prepareStatement("insert into mdm.material(code,name,category,source_category,source_module,status,created_by,updated_by) values (?,?,?,'SYNTHETIC','AI','ACTIVE',?,?) on conflict (code) do nothing")) {
            for (var value : values) { s.setString(1, value[0]); s.setString(2, value[1]); s.setString(3, value[2]); s.setObject(4, actor); s.setObject(5, actor); s.addBatch(); } s.executeBatch();
        }
    }
}
