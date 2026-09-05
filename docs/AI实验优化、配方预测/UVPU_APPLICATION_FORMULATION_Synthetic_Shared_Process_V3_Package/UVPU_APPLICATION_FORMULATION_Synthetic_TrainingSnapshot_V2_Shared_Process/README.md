# UVPU_APPLICATION_FORMULATION Synthetic TrainingSnapshot V2 — Shared Process

- 400 SYNTHETIC experiments
- 67 source report sheets
- fixed seed: 20260903
- development only; production_eligible=false

## Key semantic rule

The original application report uses **public/shared process + multiple formulations**. For each APP-xxx sheet, rows 2 and 5–7 define shared temperature, humidity, substrate, coating method and UV cure conditions. During normalization, those shared conditions are copied onto every experiment in D:I, so every model row has its own complete X even though the raw Excel stores the process only once.

Different APP-xxx sheets intentionally use different public process conditions. Formula composition and measured film thickness remain experiment-level fields.

`/（未测试）` in the Excel and IEEE NaN in the snapshot both mean missing/not tested, never zero.
