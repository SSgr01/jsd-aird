package com.jsd.aird.rnd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExperimentSourceTypeTest {

    @Test
    void defaultsBlankValuesToManual() {
        assertThat(ExperimentSourceType.fromNullable(null)).isEqualTo(ExperimentSourceType.MANUAL);
        assertThat(ExperimentSourceType.fromNullable("  ")).isEqualTo(ExperimentSourceType.MANUAL);
    }

    @Test
    void normalizesSchemaCompatibleSourceValues() {
        assertThat(ExperimentSourceType.fromNullable(" excel_import "))
                .isEqualTo(ExperimentSourceType.EXCEL_IMPORT);
        assertThat(ExperimentSourceType.fromNullable("ocr_import"))
                .isEqualTo(ExperimentSourceType.OCR_IMPORT);
    }

    @Test
    void rejectsAmbiguousDataImportSource() {
        assertThatThrownBy(() -> ExperimentSourceType.fromNullable("DATA_IMPORT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXCEL_IMPORT")
                .hasMessageContaining("OCR_IMPORT");
    }
}
