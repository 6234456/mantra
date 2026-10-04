// Independent exact rational tariff source used only to create five draft boundary references.
// No engine inputs or output amounts are read. Python adapter recomputes these with Decimal/Fraction.
var sourceParametersText = "{\n  \"checked_on\": \"2026-10-04\",\n  \"applicability_years\": [\n    2024,\n    2025\n  ],\n  \"currency\": \"EUR\",\n  \"loss\": {\n    \"single_allowance\": \"1000000\",\n    \"joint_allowance\": \"2000000\",\n    \"excess_fraction\": \"0.70\",\n    \"carryback_policy\": \"Explicit full waiver; no carryback calculation. Integral EUR facts.\"\n  },\n  \"trade\": {\n    \"year\": 2025,\n    \"financing_allowance\": \"200000\",\n    \"addition_fraction\": \"0.25\",\n    \"weighted_fractions\": {\n      \"debt\": \"1\",\n      \"movable-rent\": \"0.20\",\n      \"immovable-rent\": \"0.50\",\n      \"licence\": \"0.25\"\n    },\n    \"natural_person_allowance\": \"24500\",\n    \"assessment_fraction\": \"0.035\",\n    \"minimum_hebesatz_percent\": \"200\",\n    \"hebesatz_unit\": \"percentage points; 400 means 400%, multiplier 4\",\n    \"tax_rounding\": \"Frozen amounts are exact cents; no additional legal rounding claim.\"\n  },\n  \"income_2025\": {\n    \"basic_allowance\": \"12096\",\n    \"zone2_upper\": \"17443\",\n    \"zone3_upper\": \"68480\",\n    \"zone4_upper\": \"277825\",\n    \"zone2_quadratic\": \"932.30\",\n    \"zone2_linear\": \"1400\",\n    \"zone3_quadratic\": \"176.64\",\n    \"zone3_linear\": \"2397\",\n    \"zone3_constant\": \"1015.13\",\n    \"zone4_fraction\": \"0.42\",\n    \"zone4_subtraction\": \"10911.92\",\n    \"zone5_fraction\": \"0.45\",\n    \"zone5_subtraction\": \"19246.67\",\n    \"special_expense_flat\": \"36\",\n    \"credit_factor\": \"4\",\n    \"soli_threshold\": \"19950\",\n    \"soli_fraction\": \"0.055\",\n    \"soli_taper\": \"0.119\",\n    \"tariff_rounding\": \"Taxable income and tariff each floor to integral EUR. Soli floors to cents.\"\n  },\n  \"converge\": {\n    \"money_scale\": 2,\n    \"rounding\": \"half-up (ties away from zero)\",\n    \"callback_rounding\": \"Every callback result rounds to cents. Closed-form exact values are separate evidence.\",\n    \"stop\": \"Call callback, then accept next when abs(next-current)<=tolerance; maximum counts callback calls.\",\n    \"max_default\": 60,\n    \"tolerance_default\": \"0\",\n    \"not_converged\": \"No approximate numeric result returned.\",\n    \"budget\": \"Resource limits are separate technical failure; code/details pending public API.\"\n  }\n}\n";

function bigGcd(a,b){a=a<0n?-a:a;b=b<0n?-b:b;while(b){var t=a%b;a=b;b=t;}return a;}

function rational(x,den=1n){if(typeof x==='object'&&x.n!==undefined)return x;var n;if(typeof x==='string'&&x.includes('.')){var negative=x.startsWith('-'),p=x.replace('-','').split('.');n=BigInt(p[0]+p[1]);if(negative)n=-n;den=10n**BigInt(p[1].length);}else n=BigInt(x);if(den<0n){den=-den;n=-n;}var g=bigGcd(n,den);return {n:n/g,d:den/g};}

function qadd(a,b){a=rational(a);b=rational(b);return rational(a.n*b.d+b.n*a.d,a.d*b.d);}

function qneg(a){a=rational(a);return rational(-a.n,a.d);}

function qsub(a,b){return qadd(a,qneg(b));}

function qmul(a,b){a=rational(a);b=rational(b);return rational(a.n*b.n,a.d*b.d);}

function qdiv(a,b){a=rational(a);b=rational(b);if(!b.n)throw Error('division0');return rational(a.n*b.d,a.d*b.n);}

function qcmp(a,b){a=rational(a);b=rational(b);var n=a.n*b.d-b.n*a.d;return n<0n?-1:n>0n?1:0;}

function qfloor(a,scale=0){a=rational(a);var unit=10n**BigInt(Math.abs(scale));var t=scale>=0?qmul(a,unit):qdiv(a,unit);var n=t.n/t.d;if(t.n<0n&&t.n%t.d)n--;return scale>=0?rational(n,unit):rational(n*unit);}

function qhalf(a,scale){a=rational(a);var unit=10n**BigInt(scale),t=qmul(a,unit),neg=t.n<0n,n=neg?-t.n:t.n,v=n/t.d;if(2n*(n%t.d)>=t.d)v++;return rational(neg?-v:v,unit);}

function qtext(a){a=rational(a);var neg=a.n<0n,n=neg?-a.n:a.n,whole=n/a.d,rem=n%a.d,out=(neg?'-':'')+whole; if(rem){out+='.';for(var i=0;rem&&i<80;i++){rem*=10n;out+=(rem/a.d).toString();rem%=a.d;}if(rem)throw Error('nonterminating decimal must explicitly round');}return out;}

function primaryTariff(income){var x=qfloor(income),p=JSON.parse(sourceParametersText).income_2025,raw;if(qcmp(x,p.basic_allowance)<=0)raw=rational(0);else if(qcmp(x,p.zone2_upper)<=0){var y=qdiv(qsub(x,p.basic_allowance),10000);raw=qadd(qmul(p.zone2_quadratic,qmul(y,y)),qmul(p.zone2_linear,y));}else if(qcmp(x,p.zone3_upper)<=0){var z=qdiv(qsub(x,p.zone2_upper),10000);raw=qadd(qadd(qmul(p.zone3_quadratic,qmul(z,z)),qmul(p.zone3_linear,z)),p.zone3_constant);}else if(qcmp(x,p.zone4_upper)<=0)raw=qsub(qmul(p.zone4_fraction,x),p.zone4_subtraction);else raw=qsub(qmul(p.zone5_fraction,x),p.zone5_subtraction);return qfloor(raw);}
