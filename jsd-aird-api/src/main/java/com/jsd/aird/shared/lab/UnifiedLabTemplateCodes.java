package com.jsd.aird.shared.lab;

/** Stable identities for the two standard experiment templates. */
public final class UnifiedLabTemplateCodes {
    public static final String TEST="EXP_TEST_UNIFIED";
    public static final String SYNTHESIS="EXP_SYNTHESIS_UNIFIED";
    private UnifiedLabTemplateCodes() {}
    public static boolean contains(String code) { return TEST.equals(code)||SYNTHESIS.equals(code); }
}
