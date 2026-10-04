# Gewerbesteuer 2025 demonstration

**Uncompiled M3 application draft.** The application is a fictional natural-person sole-proprietor demonstration, not production tax software. No application acceptance has yet been run. It is not a published library artifact.

The exact identity is de.gewst/2025@2025.1. Its own parameter set and layout have unique IDs. Twenty frozen cases cover the financing allowance boundary, full-hundred assessment rounding, the personal allowance boundary, three municipality rates, positive-income allocation limits, negative other income, zero income, negative expense, missing classification, an unsupported entity and the historical rate boundary. The typed CSV case reuses the same rate-400 facts; it is an import variant, not an additional independently authored tax example.

Specified ordinary financing expenses are already deducted from profit. Their eligibility is supplied, not inferred. Selected weights are 1 for debt, 1/5 for movable rents, 1/2 for immovable rents and 1/4 for licences. The weighted excess above EUR 200,000 is multiplied by 1/4. Given trade profit plus this addition is floored to full hundreds; the selected natural-person allowance is EUR 24,500 and the assessment fraction is 3.5%. Hebesatz is supplied in percentage points: 400 means four times the assessment amount. Every frozen due amount is exactly representable in cents. No additional legal rounding rule is claimed.

The historical minimum for these 2025 facts is 200%. The later 280% amendment first applies to 2027, so current consolidated text is not silently substituted. The fictional below-minimum case retains its computed selected scenario and reports a BUSINESS error. The corporation fixture likewise reports unsupported entity scope while displaying the sole-proprietor scenario; it does not claim a corporate tax calculation. Missing classification is a table-column completeness finding, with zero selected weighting rather than a guessed category. Negative expense remains signed and has a per-cost check. Its frozen semantic row-0/amount finding maps to actual cost coordinate F1; no amount is changed.

Simplifications: no special §8 categories, §9 deductions, trade-loss carryforward, foreign branches, own-property relief, donations, qualifying dividends, partnerships or alternate entity rules. Supplied flags establish those exclusions. The schemes comment these omissions.

Primary context: [§8 GewStG](https://www.gesetze-im-internet.de/gewstg/__8.html), [consolidated §§7/11 and §36 temporal rule](https://www.gesetze-im-internet.de/gewstg/BJNR009790936.html), [official historical §11](https://amtliche-handbuecher.bundesfinanzministerium.de/gewsth/2024/A-Gewerbesteuergesetz/II-Bemessung-der-Gewerbesteuer/Paragraf-11/paragraf-11.html), and [official historical §16](https://ao.bundesfinanzministerium.de/gewsth/2024/A-Gewerbesteuergesetz/V-Entstehung-Festsetzung-und-Erhebung-der-Steuer/Paragraf-16/inhalt.html). Project-authored JSON facts and independently computed Decimal/Fraction references supply all expected numbers. Official sources support selected concepts and dates; no official numerical example or substantial text is copied.

import-template.csv declares id/category/amount. data/financing-costs.csv supplies the rate-400 example through public typed source binding with delimiter comma, decimal point and grouping disabled. The case does not also provide that table locally. Category and id are schema-directed keywords, not application-specific parser behavior.

Six version-pinned ESt consumer cases live under the neighboring application's 2025.3 version and refer to this application's case files by relative path. They transfer messbetrag@[] and gewerbesteuer@[] to gewst-messbetrag@[:A] and gewst-due@[:A]. Fractional-cap and below-minimum source cases have no certified final ESt credit; their independent source references stop at the declared intermediate boundary.

After migration run:

    python3 apps/de-gewst/verify_m3_expected.py --self-test
    python3 apps/de-gewst/verify_m3_expected.py --outputs apps/de-gewst/build/out

Between those commands run public-API application acceptance. All ordinary cases, including BUSINESS failures, must render HTML/Text/Workbench JSON/XLSX and compare visible numeric cells and reductions independently. No Excel fallback or special workbench route is allowed. Tests must verify missing-category row/column coordinates and signed-invalid facts as well as amounts. Draft source checks alone do not establish acceptance.
