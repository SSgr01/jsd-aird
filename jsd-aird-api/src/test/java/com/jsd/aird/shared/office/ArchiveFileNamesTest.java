package com.jsd.aird.shared.office;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ArchiveFileNamesTest {
    @Test
    void keepsExtensionsAndAvoidsCollisionsOnCaseInsensitiveFilesystems() {
        var names = new HashSet<String>();
        assertThat(ArchiveFileNames.unique("树脂报告.xlsx", names)).isEqualTo("树脂报告.xlsx");
        assertThat(ArchiveFileNames.unique("树脂报告.xlsx", names)).isEqualTo("树脂报告_2.xlsx");
        assertThat(ArchiveFileNames.unique("树脂报告.XLSX", names)).isEqualTo("树脂报告_3.XLSX");
        assertThat(ArchiveFileNames.unique("../目录/报告.pdf", names)).doesNotContain("/", "\\").endsWith(".pdf");
        assertThat(ArchiveFileNames.unique("长".repeat(300) + ".docx", names)).hasSize(165).endsWith(".docx");
    }
}
