package com.jsd.aird.kb.infrastructure;

import com.jsd.aird.kb.domain.DocumentParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reconstructs ruled tables from their pixels; OCR text is only placed after the grid is known. */
final class ImageTableGeometryDetector {
    record Word(String text, double left, double top, double right, double bottom, double confidence) {
        double centerX() { return (left + right) / 2d; }
        double centerY() { return (top + bottom) / 2d; }
    }

    record Result(List<DocumentParser.TextBlock> blocks, double score, int brokenLines,
                  int unassignedWords, String fallbackReason) {
        boolean reliable() { return fallbackReason == null && score >= .72 && !blocks.isEmpty(); }
    }

    Result detect(byte[] bytes, List<Word> words, Integer pageNo) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return failed("IMAGE_DECODE_FAILED");
            int width = image.getWidth(), height = image.getHeight();
            boolean[][] dark = threshold(image);
            List<Integer> xs = linePositions(dark, true, width, height);
            List<Integer> ys = linePositions(dark, false, width, height);
            if (xs.size() < 3 || ys.size() < 3) return failed("RULED_GRID_NOT_FOUND");

            // Ignore isolated page ornaments: retain the widest grid interval and rows that cross it.
            int left = xs.getFirst(), right = xs.getLast();
            List<Integer> usableY = ys.stream().filter(y -> horizontalCoverage(dark, y, left, right) >= .55).toList();
            if (usableY.size() >= 3) ys = usableY;
            int rowCount=ys.size()-1, columnCount=xs.size()-1;
            var components = new Components(rowCount*columnCount);
            int broken = 0;
            for(int row=0;row<rowCount;row++) for(int column=0;column<columnCount-1;column++) {
                if(verticalCoverage(dark,xs.get(column+1),ys.get(row),ys.get(row+1))<.48) {
                    components.union(row*columnCount+column,row*columnCount+column+1); broken++;
                }
            }
            for(int row=0;row<rowCount-1;row++) for(int column=0;column<columnCount;column++) {
                if(horizontalCoverage(dark,ys.get(row+1),xs.get(column),xs.get(column+1))<.48) {
                    components.union(row*columnCount+column,(row+1)*columnCount+column); broken++;
                }
            }
            var areas=new LinkedHashMap<Integer,Area>();
            for(int row=0;row<rowCount;row++) for(int column=0;column<columnCount;column++) {
                int root=components.find(row*columnCount+column);
                var area=areas.get(root);
                if(area==null){ area=new Area(row,column); areas.put(root,area); }
                area.include(row,column);
            }

            var blocks = new ArrayList<DocumentParser.TextBlock>();
            var assigned = new boolean[words.size()];
            for (int row = 0; row < rowCount; row++) {
                final int currentRow = row;
                int top = ys.get(row), bottom = ys.get(row + 1);
                var cells = new ArrayList<Map<String, Object>>();
                for (var area : areas.values().stream().filter(value -> value.minRow==currentRow)
                        .sorted(Comparator.comparingInt(value -> value.minColumn)).toList()) {
                    int cellLeft=xs.get(area.minColumn), cellRight=xs.get(area.maxColumn+1);
                    int cellTop=ys.get(area.minRow), cellBottom=ys.get(area.maxRow+1);
                    var text = new ArrayList<Word>();
                    for (int index = 0; index < words.size(); index++) {
                        var word = words.get(index);
                        if (word.centerX() >= cellLeft && word.centerX() <= cellRight
                                && word.centerY() >= cellTop && word.centerY() <= cellBottom) {
                            text.add(word); assigned[index] = true;
                        }
                    }
                    text.sort(Comparator.comparingDouble(Word::centerY).thenComparingDouble(Word::centerX));
                    var cell = new LinkedHashMap<String, Object>();
                    cell.put("text", joinWords(text, Math.max(4, cellBottom - cellTop)));
                    cell.put("rowSpan", area.maxRow-area.minRow+1);
                    cell.put("columnSpan", area.maxColumn-area.minColumn+1);
                    cell.put("logicalColumn", area.minColumn);
                    cell.put("header", row == 0);
                    cell.put("pixelLeft", cellLeft); cell.put("pixelTop", cellTop);
                    cell.put("pixelRight", cellRight); cell.put("pixelBottom", cellBottom);
                    cell.put("borderTop", true); cell.put("borderRight", true);
                    cell.put("borderBottom", true); cell.put("borderLeft", true);
                    cell.put("geometryConfidence", Math.min(1d,
                            (horizontalCoverage(dark, top, cellLeft, cellRight)
                                    + horizontalCoverage(dark, bottom, cellLeft, cellRight)) / 2d));
                    cells.add(Map.copyOf(cell));
                }
                var attributes = new LinkedHashMap<String, Object>();
                attributes.put("tableGroup", "image-geometry-table");
                attributes.put("tableRowNo", row);
                attributes.put("tableColumnCount", columnCount);
                attributes.put("pixelTop", top); attributes.put("pixelBottom", bottom);
                attributes.put("imageWidth", width); attributes.put("imageHeight", height);
                attributes.put("geometrySource", "image-lines+positioned-ocr");
                attributes.put("cells", List.copyOf(cells));
                blocks.add(new DocumentParser.TextBlock(pageNo, "table-row",
                        cells.stream().map(c -> String.valueOf(c.get("text"))).reduce((a,b) -> a + " | " + b).orElse(""),
                        null, null, null, List.of((double) left, (double) top, (double) right, (double) bottom),
                        null, null, null, Map.copyOf(attributes)));
            }
            int unassigned = 0;
            for (boolean value : assigned) if (!value) unassigned++;
            double assignedScore = words.isEmpty() ? .65 : 1d - unassigned / (double) words.size();
            double score = Math.max(0, Math.min(1, .65 + .35 * assignedScore - Math.min(.2, broken * .002)));
            return new Result(List.copyOf(blocks), score, broken, unassigned,
                    score >= .72 ? null : "GEOMETRY_SCORE_LOW");
        } catch (Exception exception) {
            return failed("GEOMETRY_DETECTION_FAILED");
        }
    }

    private Result failed(String reason) { return new Result(List.of(), 0, 0, 0, reason); }

    private boolean[][] threshold(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        long sum = 0;
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            int rgb=image.getRGB(x,y); sum += (((rgb>>16)&255)*30 + ((rgb>>8)&255)*59 + (rgb&255)*11)/100;
        }
        int limit = Math.max(80, Math.min(210, (int)(sum/(long)(w*h)) - 35));
        boolean[][] result = new boolean[h][w];
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            int rgb=image.getRGB(x,y); int gray=(((rgb>>16)&255)*30 + ((rgb>>8)&255)*59 + (rgb&255)*11)/100;
            result[y][x]=gray<limit;
        }
        return result;
    }

    private List<Integer> linePositions(boolean[][] dark, boolean vertical, int width, int height) {
        int length = vertical ? width : height;
        var candidates = new ArrayList<Integer>();
        for (int p=0;p<length;p++) {
            double coverage = vertical ? verticalCoverage(dark,p,0,height-1) : horizontalCoverage(dark,p,0,width-1);
            if (coverage >= (vertical ? .18 : .50)) candidates.add(p);
        }
        var clustered = new ArrayList<Integer>();
        for (int p : candidates) {
            if (clustered.isEmpty() || p-clustered.getLast()>3) clustered.add(p);
            else clustered.set(clustered.size()-1, (clustered.getLast()+p)/2);
        }
        return List.copyOf(clustered);
    }

    private double verticalCoverage(boolean[][] dark,int x,int top,int bottom) {
        int hit=0,total=Math.max(1,bottom-top+1);
        for(int y=Math.max(0,top);y<=Math.min(dark.length-1,bottom);y++) {
            boolean value=false; for(int dx=-1;dx<=1;dx++) if(x+dx>=0&&x+dx<dark[0].length) value|=dark[y][x+dx];
            if(value) hit++;
        }
        return hit/(double)total;
    }
    private double horizontalCoverage(boolean[][] dark,int y,int left,int right) {
        int hit=0,total=Math.max(1,right-left+1); y=Math.max(0,Math.min(dark.length-1,y));
        for(int x=Math.max(0,left);x<=Math.min(dark[0].length-1,right);x++) {
            boolean value=false; for(int dy=-1;dy<=1;dy++) if(y+dy>=0&&y+dy<dark.length) value|=dark[y+dy][x];
            if(value) hit++;
        }
        return hit/(double)total;
    }
    private String joinWords(List<Word> words,int rowHeight) {
        var result=new StringBuilder(); double previousY=Double.NaN;
        for(var word:words){
            if(result.length()>0) result.append(!Double.isNaN(previousY)&&word.centerY()-previousY>rowHeight*.25?'\n':' ');
            result.append(word.text().strip()); previousY=word.centerY();
        }
        return result.toString().strip();
    }

    private static final class Components {
        private final int[] parent;
        Components(int size){parent=new int[size];for(int i=0;i<size;i++)parent[i]=i;}
        int find(int value){while(parent[value]!=value){parent[value]=parent[parent[value]];value=parent[value];}return value;}
        void union(int left,int right){int a=find(left),b=find(right);if(a!=b)parent[b]=a;}
    }
    private static final class Area {
        int minRow,maxRow,minColumn,maxColumn;
        Area(int row,int column){minRow=maxRow=row;minColumn=maxColumn=column;}
        Area include(int row,int column){minRow=Math.min(minRow,row);maxRow=Math.max(maxRow,row);
            minColumn=Math.min(minColumn,column);maxColumn=Math.max(maxColumn,column);return this;}
    }
}
