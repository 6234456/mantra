#!/usr/bin/env python3
"""Independent recomputation of the Mustermann 2025 case with Python's exact decimal arithmetic.

It shares no code with the Mantra engine or the schema; it re-implements the statutory rules used in
schema.mantra so the expected values in EinkommensteuerTest.kt can be checked by a second route.
Run: python3 examples/de-est-2025/verify_expected.py
"""
from decimal import Decimal as D, ROUND_FLOOR


def floor(x, places=0):
    return x.quantize(D(1).scaleb(-places), rounding=ROUND_FLOOR)


PARAMS = {
    2025: dict(gfb=12096, g2=17443, g3=68480, g4=277825, a2=D("932.30"), a3=D("176.64"), c3=D("1015.13"),
               c4=D("10911.92"), c5=D("19246.67"), kfb=3336, kindergeld=255, soli=19950, av=29344),
    2026: dict(gfb=12348, g2=17799, g3=69878, g4=277825, a2=D("914.51"), a3=D("173.10"), c3=D("1034.87"),
               c4=D("11135.63"), c5=D("19470.38"), kfb=3414, kindergeld=259, soli=20350, av=30826),
}


def grundtarif(zve, p):
    """§ 32a Abs. 1 EStG with the zone parameters of one year."""
    x = floor(zve)
    if x <= p["gfb"]:
        tax = D(0)
    elif x <= p["g2"]:
        y = (x - p["gfb"]) / D(10000)
        tax = (p["a2"] * y + 1400) * y
    elif x <= p["g3"]:
        z = (x - p["g2"]) / D(10000)
        tax = (p["a3"] * z + 2397) * z + p["c3"]
    elif x <= p["g4"]:
        tax = D("0.42") * x - p["c4"]
    else:
        tax = D("0.45") * x - p["c5"]
    return floor(tax)


def splitting(zve, p):
    """§ 32a Abs. 5 EStG."""
    return 2 * grundtarif(zve / 2, p)


def stepwise(amount, bands):
    total, lower = D(0), D(0)
    for upper, rate in bands:
        top = amount if upper is None else min(amount, D(upper))
        if top > lower:
            total += (top - lower) * D(rate)
        if upper is None or amount <= upper:
            break
        lower = D(upper)
    return total


def compute(p):
    # Einkünfte
    nsa_a = D(68500) - max(D(2340), D(1230))
    nsa_b = D(31200) - min(D(1230), D(31200))  # 650 < Pauschbetrag
    gewerbe_a = D(12400)
    vuv = D(9600) - D(2450) - D(2180) - D(1120)
    private_sales_b = D(0) if D(740) < 1000 else D(740)  # Freigrenze § 23 Abs. 3 Satz 5
    summe = nsa_a + nsa_b + gewerbe_a + vuv + private_sales_b
    gde = summe

    # Sonderausgaben
    def vorsorge(rv, ag, basis, weitere, p):
        alters = max(D(0), min(rv, D(p["av"])) - ag)
        sonstige = max(basis, min(basis + weitere, D(1900)))
        return alters + sonstige

    vorsorge_a = vorsorge(D("12741.00"), D("6370.50"), D(5480), D(820), p)
    vorsorge_b = vorsorge(D("5803.20"), D("2901.60"), D(2610), D(380), p)
    kist_paid, spenden = D(1210), min(D(450), D("0.2") * gde)
    pausch = max(D(0), D(72) - (kist_paid + spenden))
    schulgeld = min(D(5000), D("0.3") * 1800)
    sonderausgaben = vorsorge_a + vorsorge_b + kist_paid + spenden + pausch + schulgeld

    # Außergewöhnliche Belastungen (1 Kind: 2 / 3 / 4 %)
    zumutbar = stepwise(gde, [(15340, "0.02"), (51130, "0.03"), (None, "0.04")])
    agb = max(D(0), D(3100) - zumutbar)

    einkommen = gde - sonderausgaben - agb

    # Günstigerprüfung § 31
    kfb = 1 * 2 * (D(p["kfb"]) + D(1464))
    kindergeld = 1 * D(p["kindergeld"]) * 12
    est_ohne = splitting(einkommen, p)
    est_mit = splitting(einkommen - kfb, p)
    kfb_better = est_mit + kindergeld < est_ohne
    zve = einkommen - (kfb if kfb_better else 0)

    tarif = splitting(zve, p)
    erm_35a = min(max(D(0), tarif), min(D("0.2") * 1200, D(4000)) + min(D("0.2") * 2500, D(1200)))
    festzusetzen = tarif - erm_35a + (kindergeld if kfb_better else 0)

    bmg = max(D(0), splitting(zve - (0 if kfb_better else kfb), p) - erm_35a)
    soli_grenze = 2 * D(p["soli"])
    soli = D(0) if bmg <= soli_grenze else floor(min(D("0.055") * bmg, D("0.119") * (bmg - soli_grenze)), 2)
    kist = floor(D("0.09") * bmg, 2)
    festgesetzt = festzusetzen + soli + kist
    ergebnis = festgesetzt - (D(11020) + D(3180)) - (D("991.80") + D("286.20")) - D(2400)

    abgeltung = floor(D("0.25") * (D(1850) - 1000), 2)
    abgeltung_soli = floor(D("0.055") * abgeltung, 2)

    rows = [
        ("summe-der-einkuenfte", summe), ("vorsorgeaufwendungen A", vorsorge_a), ("vorsorgeaufwendungen B", vorsorge_b),
        ("sonderausgaben-summe", sonderausgaben), ("zumutbare-belastung", zumutbar), ("agb-summe", agb),
        ("einkommen", einkommen), ("est-ohne-kfb", est_ohne), ("est-mit-kfb", est_mit), ("kfb-guenstiger", kfb_better),
        ("zu-versteuerndes-einkommen", zve), ("tarifliche-est", tarif), ("ermaessigung-35a", erm_35a),
        ("festzusetzende-est", festzusetzen), ("solidaritaetszuschlag", soli), ("kirchensteuer", kist),
        ("abrechnungsergebnis", ergebnis), ("abgeltungsteuer", abgeltung), ("abgeltung-soli", abgeltung_soli),
    ]
    return rows


for year in (2025, 2026):
    print(f"--- parameters {year}")
    for name, value in compute(PARAMS[year]):
        print(f"{name:32s} {value}")
