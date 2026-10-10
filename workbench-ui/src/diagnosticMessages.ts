import type { Language } from './i18n'
import type { Diagnostic } from './types'

/** Stable code explanations; runtime details remain separate and are never translated or rewritten. */
const catalog: Record<string, Readonly<Record<Language, string>>> = {
  'DSL-MANTRA-ALLOC-ZERO-BASIS': {
    en: 'Allocation weights sum to zero, so the requested allocation is undefined.',
    de: 'Die Summe der Verteilungsgewichte ist null; die gewünschte Verteilung ist deshalb nicht definiert.',
  },
  'DSL-MANTRA-BAND-ROW': {
    en: 'A lookup band is not a two-item threshold/value row.',
    de: 'Eine Stufe der Nachschlagetabelle besteht nicht aus genau zwei Einträgen für Schwelle und Wert.',
  },
  'DSL-MANTRA-CALC-ITERATIONS': {
    en: 'Convergence maximum must be an exact integer from 1 through 1000.',
    de: 'Die maximale Anzahl der Konvergenzschritte muss eine ganze Zahl von 1 bis einschließlich 1000 sein.',
  },
  'DSL-MANTRA-CALC-NOT-CONVERGED': {
    en: 'No adjacent iterate meets the tolerance within the declared maximum; no iterate becomes a result.',
    de: 'Innerhalb der maximalen Schrittzahl erreicht kein Paar aufeinanderfolgender Werte die Toleranz. Kein Zwischenwert wird als Ergebnis verwendet.',
  },
  'DSL-MANTRA-CALC-NUMBER': {
    en: 'Convergence requires a defined scalar numeric seed, tolerance, bound and callback result.',
    de: 'Die Konvergenz verlangt definierte skalare Zahlen für Startwert, Toleranz, Grenze und Rückgabewert der Funktion.',
  },
  'DSL-MANTRA-CALC-TOLERANCE': {
    en: 'Convergence tolerance must be nonnegative.',
    de: 'Die Konvergenztoleranz darf nicht negativ sein.',
  },
  'DSL-MANTRA-INTEGER-REQUIRED': {
    en: 'A library argument requiring an integer is fractional or invalid.',
    de: 'Ein Argument, das eine ganze Zahl verlangt, ist nicht ganzzahlig oder ungültig.',
  },
  'DSL-MANTRA-MAP-REQUIRED': {
    en: 'A dimension or allocation argument is not a member map.',
    de: 'Ein Dimensions- oder Verteilungsargument ist keine Zuordnung von Mitgliedern zu Werten.',
  },
  'DSL-MANTRA-NUMBER-REQUIRED': {
    en: 'A numeric library argument is not a number.',
    de: 'Ein numerisches Funktionsargument ist keine Zahl.',
  },
  'DSL-MANTRA-PMT-PERIODS': {
    en: 'Payment calculation has a nonpositive number of periods.',
    de: 'Die Zahlungsberechnung verlangt eine positive Anzahl von Perioden.',
  },
  'DSL-MANTRA-PREV': {
    en: 'Previous-period reference has an invalid target or ambiguous period context',
    de: 'Der Verweis auf die Vorperiode hat ein ungültiges Ziel oder einen mehrdeutigen Periodenkontext.',
  },
  'DSL-MANTRA-ROLLUP-BOUNDARY': {
    en: 'Five-argument rollup needs a first/last boundary policy',
    de: 'Die Variante von rollup mit fünf Argumenten benötigt eine Regel für den ersten oder letzten Wert.',
  },
  'DSL-MANTRA-ROLLUP-KEY': {
    en: 'A source member has no parent relation for rollup.',
    de: 'Für ein Quellmitglied fehlt die Zuordnung zu einem übergeordneten Mitglied.',
  },
  'DSL-MANTRA-ROLLUP-ORDER': {
    en: 'Boundary rollup needs an explicit unique sequence of period keys',
    de: 'Die Aggregation nach erstem oder letztem Wert benötigt eine ausdrücklich angegebene, eindeutige Folge von Periodenschlüsseln.',
  },
  'DSL-MANTRA-STEPWISE-BAND': {
    en: 'A progressive band is not an upper-limit/rate pair.',
    de: 'Eine progressive Stufe besteht nicht aus einem Paar von Obergrenze und Satz.',
  },
  'DSL-MANTRA-TABLE-CRITERIA': {
    en: 'A condition-selection criterion is not a supported scalar value.',
    de: 'Ein Auswahlkriterium ist kein unterstützter skalarer Wert.',
  },
  'DSL-MANTRA-TABLE-KEY': {
    en: 'A record key or selected value column is not a keyword.',
    de: 'Ein Datensatzschlüssel oder die ausgewählte Wertspalte ist kein Schlüsselwort.',
  },
  'DSL-MANTRA-TABLE-NUMBER': {
    en: 'A matched record has an absent, nil or nonnumeric sum value.',
    de: 'In einem passenden Datensatz fehlt der Summenwert, ist nil oder ist nicht numerisch.',
  },
  'DSL-MANTRA-TABLE-RECORD': {
    en: 'A condition-selection record or criteria value is not a keyword-keyed map.',
    de: 'Ein Datensatz oder die Auswahlkriterien sind keine Zuordnung mit Schlüsselwörtern als Schlüsseln.',
  },
  'DSL-MANTRA-TABLE-ROWS': {
    en: 'A condition-selection input is not a concrete ordered vector or sequence.',
    de: 'Die Eingabe für die bedingte Auswahl ist kein konkreter geordneter Vektor und keine solche Sequenz.',
  },
  'MANTRA-AGGREGATE': {
    en: 'An aggregate declaration is malformed or has invalid ratio references.',
    de: 'Die Aggregationsdeklaration ist fehlerhaft oder enthält ungültige Verweise für eine Verhältniszahl.',
  },
  'MANTRA-AGGREGATE-BOUNDARY': {
    en: 'First/last aggregation needs a numeric node containing the declared period axis',
    de: 'Die Aggregation nach erstem oder letztem Wert benötigt einen numerischen Knoten mit der deklarierten Periodenachse.',
  },
  'MANTRA-AGGREGATE-DIVISION': {
    en: 'An aggregate quotient needs explicit rounding because its decimal expansion is nonterminating.',
    de: 'Der Quotient hat keine endliche Dezimaldarstellung und benötigt eine ausdrücklich festgelegte Rundung.',
  },
  'MANTRA-AGGREGATE-REFERENCE': {
    en: 'A ratio numerator or denominator is missing, nonnumeric or has different dimensions.',
    de: 'Zähler oder Nenner fehlt, ist nicht numerisch oder verwendet andere Dimensionen.',
  },
  'MANTRA-AGGREGATE-TYPE': {
    en: 'Ratio aggregation is attached to a nonnumeric measure or lacks valid ratio metadata.',
    de: 'Eine Verhältnisaggregation wurde einem nicht numerischen Wert zugeordnet oder ihre Angaben sind ungültig.',
  },
  'MANTRA-AGGREGATE-ZERO-DENOMINATOR': {
    en: 'The aggregate denominator is zero; the ratio remains undefined rather than becoming zero.',
    de: 'Der aggregierte Nenner ist null. Die Verhältniszahl bleibt undefiniert und wird nicht zu null.',
  },
  'MANTRA-ALL-DYNAMIC': {
    en: 'An all-member reference cannot resolve to a static node name.',
    de: 'Der Verweis auf alle Mitglieder lässt sich keinem festen Knotennamen zuordnen.',
  },
  'MANTRA-ALL-UNKNOWN': {
    en: 'An all-member reference names an unknown calculated node.',
    de: 'Der Verweis auf alle Mitglieder nennt einen unbekannten berechneten Knoten.',
  },
  'MANTRA-AUDIT-TRUNCATED': {
    en: 'Audit capture reached its per-coordinate or run budget; missing steps are explicitly marked.',
    de: 'Die Erfassung des Rechenwegs hat das Budget pro Koordinate oder Lauf erreicht. Fehlende Schritte sind gekennzeichnet.',
  },
  'MANTRA-CALC-INVALID-BOUND': {
    en: 'A projected calculation primitive has an invalid explicit bound.',
    de: 'Eine projizierte Berechnungsfunktion hat eine ungültige ausdrücklich angegebene Grenze.',
  },
  'MANTRA-CALC-NOT-CONVERGED': {
    en: 'Bounded convergence exhausted its maximum; the failed node is nil with genuine failure evidence.',
    de: 'Die begrenzte Konvergenz hat ihre maximale Schrittzahl ausgeschöpft. Der fehlgeschlagene Knoten ist nil und behält echte Fehlernachweise.',
  },
  'MANTRA-CALC-NUMBER': {
    en: 'A projected calculation primitive requires a defined numeric scalar.',
    de: 'Eine projizierte Berechnungsfunktion benötigt eine definierte skalare Zahl.',
  },
  'MANTRA-CASE-BIND': {
    en: 'A case formula binding is malformed.',
    de: 'Die Formelbindung des Falls ist fehlerhaft.',
  },
  'MANTRA-CASE-BIND-DUPLICATE': {
    en: 'A case binds the same formula slot more than once.',
    de: 'Der Fall bindet denselben Formelplatz mehrfach.',
  },
  'MANTRA-CASE-BIND-UNKNOWN': {
    en: 'A case binds a formula slot not declared by the schema.',
    de: 'Der Fall bindet einen Formelplatz, den das Schema nicht deklariert.',
  },
  'MANTRA-CASE-DUPLICATE': {
    en: 'The same case input or parameter key is supplied more than once.',
    de: 'Ein Eingabe- oder Parameterschlüssel ist im Fall mehrfach angegeben.',
  },
  'MANTRA-CASE-EXTEND': {
    en: 'A case extension is malformed.',
    de: 'Eine Erweiterung des Falls ist fehlerhaft.',
  },
  'MANTRA-CASE-FORM': {
    en: 'The case contains an unsupported declaration form.',
    de: 'Der Fall enthält eine nicht unterstützte Deklarationsform.',
  },
  'MANTRA-CASE-ID': {
    en: 'The case identifier is missing.',
    de: 'Die Kennung des Falls fehlt.',
  },
  'MANTRA-CASE-INPUT-UNKNOWN': {
    en: 'The case supplies an input not declared by its schema.',
    de: 'Der Fall liefert eine Eingabe, die sein Schema nicht deklariert.',
  },
  'MANTRA-CASE-LINK': {
    en: 'A link declaration lacks a literal path, exact schema/version, or complete mapping records.',
    de: 'Der Verknüpfungsdeklaration fehlt ein literaler Pfad, eine genaue Schema- und Versionsangabe oder eine vollständige Zuordnung.',
  },
  'MANTRA-CASE-PARAM-UNKNOWN': {
    en: 'The case overrides a parameter not declared by its schema.',
    de: 'Der Fall überschreibt einen Parameter, den sein Schema nicht deklariert.',
  },
  'MANTRA-CASE-ROOT': {
    en: 'The document is not a case document.',
    de: 'Das Dokument ist kein Falldokument.',
  },
  'MANTRA-CASE-ROWS-COLUMN': {
    en: 'A compact table header contains a non-keyword or duplicate column.',
    de: 'Der Kopf einer kompakten Tabelle enthält eine doppelte Spalte oder einen Eintrag, der kein Schlüsselwort ist.',
  },
  'MANTRA-CASE-ROWS-HEADER': {
    en: 'A compact table lacks a nonempty keyword-vector header.',
    de: 'Der kompakten Tabelle fehlt ein nicht leerer Vektor von Spaltenschlüsselwörtern.',
  },
  'MANTRA-CASE-ROWS-ROW': {
    en: 'A compact table row is not a vector matching its header length.',
    de: 'Eine Zeile der kompakten Tabelle ist kein Vektor mit genau so vielen Einträgen wie der Tabellenkopf.',
  },
  'MANTRA-CASE-SCHEMA-MISMATCH': {
    en: 'The case declares a different schema from the selected one.',
    de: 'Der Fall nennt ein anderes Schema als das ausgewählte.',
  },
  'MANTRA-CASE-SCHEMA-VERSION': {
    en: 'Case version is invalid or differs from the actual loaded schema version.',
    de: 'Die Version des Falls ist ungültig oder weicht von der tatsächlich geladenen Schemaversion ab.',
  },
  'MANTRA-CASE-SLOT-UNKNOWN': {
    en: 'A case extends an undeclared extension slot.',
    de: 'Der Fall erweitert einen nicht deklarierten Erweiterungsplatz.',
  },
  'MANTRA-CASE-SOURCE': {
    en: 'A source declaration lacks valid source options or form.',
    de: 'Eine Quellendeklaration hat keine gültigen Quellenoptionen oder eine ungültige Form.',
  },
  'MANTRA-CHECK-ARITY': {
    en: 'A check or reconciliation declaration has invalid arguments.',
    de: 'Eine Prüfungs- oder Abstimmungsdeklaration enthält ungültige Argumente.',
  },
  'MANTRA-CHECK-FAILED': {
    en: 'An applicable declarative Boolean check evaluated to false.',
    de: 'Eine anwendbare deklarative Wahrheitsprüfung hat false ergeben.',
  },
  'MANTRA-CHECK-OP': {
    en: 'A check or reconciliation attempts to contribute to a running total.',
    de: 'Eine Prüfung oder Abstimmung versucht, zu einer laufenden Summe beizutragen.',
  },
  'MANTRA-CHECK-SEVERITY': {
    en: 'A check severity is outside error or warning.',
    de: 'Der Schweregrad der Prüfung ist weder error noch warning.',
  },
  'MANTRA-CHOICE-OPTION': {
    en: 'A choice option is malformed.',
    de: 'Eine Auswahloption ist fehlerhaft.',
  },
  'MANTRA-CHOICE-RULE': {
    en: 'The choice selection rule is unsupported.',
    de: 'Die Regel zur Auswahl einer Option wird nicht unterstützt.',
  },
  'MANTRA-COMPILE-INCOMPATIBLE': {
    en: 'The compile incompatible contract failed; correct the explicit request or resources before retrying.',
    de: 'Die Bindung passt nicht zur geprüften Vorlage. Korrigieren Sie die ausdrückliche Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-COMPILE-INPUT': {
    en: 'The compile input contract failed; correct the explicit request or resources before retrying.',
    de: 'Die Eingaben erfüllen den Vertrag für die geprüfte Vorlage nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-COMPILE-PLAN': {
    en: 'The compile plan contract failed; correct the explicit request or resources before retrying.',
    de: 'Der Vertrag des übersetzten Ausführungsplans ist verletzt. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-CYCLE': {
    en: 'The calculation dependency graph contains a cycle.',
    de: 'Die Abhängigkeiten der Berechnung bilden einen Kreis.',
  },
  'MANTRA-DATA-CSV': {
    en: 'A CSV source cannot be read or lacks its declared member/mapping columns.',
    de: 'Die CSV-Quelle ist nicht lesbar oder enthält nicht die deklarierten Mitglieds- oder Zuordnungsspalten.',
  },
  'MANTRA-DATA-JSON': {
    en: 'A JSON source cannot be read or parsed.',
    de: 'Die JSON-Quelle lässt sich nicht lesen oder analysieren.',
  },
  'MANTRA-DATA-PATH': {
    en: 'A requested JSON source path is absent.',
    de: 'Der angeforderte Pfad fehlt in der JSON-Quelle.',
  },
  'MANTRA-DATA-SOURCE': {
    en: 'An external source declaration is unsupported or its file cannot be loaded.',
    de: 'Die externe Quellendeklaration wird nicht unterstützt oder ihre Datei lässt sich nicht laden.',
  },
  'MANTRA-DATA-UNKNOWN-INPUT': {
    en: 'A source mapping targets an undeclared input.',
    de: 'Eine Quellenzuordnung richtet sich an eine nicht deklarierte Eingabe.',
  },
  'MANTRA-DATA-UTF8': {
    en: 'CSV or JSON import bytes are not valid UTF-8; the import is rejected without replacing characters.',
    de: 'Die CSV- oder JSON-Importdaten sind kein gültiges UTF-8. Der Import wird abgelehnt, ohne Zeichen zu ersetzen.',
  },
  'MANTRA-DATA-XLSX': {
    en: 'A workbook source supplies no recognized named input cells.',
    de: 'Die Arbeitsmappenquelle enthält keine erkannten benannten Eingabezellen.',
  },
  'MANTRA-DATA-XLSX-CELL': {
    en: 'An input workbook cell contains an Excel error, an invalid date serial or an unsupported value; import fails without a nil or zero substitute.',
    de: 'Eine Eingabezelle der Arbeitsmappe enthält einen Excel-Fehler, eine ungültige Datumsseriennummer oder einen nicht unterstützten Wert. Der Import schlägt fehl; der Wert wird nicht durch nil oder null ersetzt.',
  },
  'MANTRA-DATA-XLSX-CONTAINER': {
    en: 'The workbook ZIP is malformed, duplicates entries, lacks required OOXML parts or has inconsistent local and central contents.',
    de: 'Das ZIP der Arbeitsmappe ist fehlerhaft, enthält doppelte Einträge, hat fehlende OOXML-Bestandteile oder widersprüchliche lokale und zentrale Inhalte.',
  },
  'MANTRA-DATA-XLSX-LIMIT': {
    en: 'A workbook import exceeds its compressed-byte, entry-count, per-entry expanded-byte or total expanded-byte limit.',
    de: 'Der Arbeitsmappenimport überschreitet die Grenze für komprimierte Bytes, die Anzahl der Einträge, die entpackten Bytes je Eintrag oder die insgesamt entpackten Bytes.',
  },
  'MANTRA-DATA-XLSX-NAME': {
    en: 'A named input cell has an unknown, incomplete or ambiguous coordinate, a conflicting sanitized name or an invalid cell reference.',
    de: 'Eine benannte Eingabezelle hat unbekannte, unvollständige oder mehrdeutige Koordinaten, einen widersprüchlichen bereinigten Namen oder einen ungültigen Zellverweis.',
  },
  'MANTRA-DEFN': {
    en: 'A schema or case function declaration is malformed.',
    de: 'Eine Funktionsdeklaration des Schemas oder Falls ist fehlerhaft.',
  },
  'MANTRA-DIMENSION': {
    en: 'A dimension declaration lacks a valid identifier, members or table source.',
    de: 'Der Dimensionsdeklaration fehlt eine gültige Kennung, eine Mitgliederliste oder eine Tabellenquelle.',
  },
  'MANTRA-DIMENSION-KEY': {
    en: 'A dimension member key is missing, invalid or duplicated.',
    de: 'Ein Mitgliedsschlüssel fehlt, ist ungültig oder mehrfach vorhanden.',
  },
  'MANTRA-DIMENSION-PARENT': {
    en: 'A child member refers to an unknown or missing parent.',
    de: 'Ein untergeordnetes Mitglied verweist auf ein fehlendes oder unbekanntes übergeordnetes Mitglied.',
  },
  'MANTRA-DIMENSION-RELATION': {
    en: 'A declared dimension parent relation is invalid.',
    de: 'Die deklarierte Beziehung zu einer übergeordneten Dimension ist ungültig.',
  },
  'MANTRA-DIMENSION-TABLE': {
    en: 'A dimension table source is missing or not a table input.',
    de: 'Die Tabellenquelle der Dimension fehlt oder ist keine Tabelleneingabe.',
  },
  'MANTRA-DIMENSION-UNKNOWN': {
    en: 'A node references an undeclared dimension.',
    de: 'Ein Knoten verweist auf eine nicht deklarierte Dimension.',
  },
  'MANTRA-EVALUATION': {
    en: 'The pinned kernel rejected a formula during evaluation; its cause code is retained in the message.',
    de: 'Der festgelegte Rechenkern hat die Formel während der Auswertung abgelehnt. Der Ursachencode steht in den Originaldetails.',
  },
  'MANTRA-FIELD': {
    en: 'A field has no corresponding declared input.',
    de: 'Zu einem Feld fehlt die entsprechende deklarierte Eingabe.',
  },
  'MANTRA-FORMULA': {
    en: 'A formula cannot compile, resolve its roots or satisfy its declared type.',
    de: 'Eine Formel lässt sich nicht übersetzen, ihre Eingabeverweise nicht auflösen oder ihr deklarierter Typ wird nicht erfüllt.',
  },
  'MANTRA-FORMULA-SLOT-OWNER': {
    en: 'A formula slot is placed outside its permitted schema context.',
    de: 'Ein Formelplatz liegt außerhalb seines zulässigen Schemakontexts.',
  },
  'MANTRA-FORMULA-SLOT-REFERENCE': {
    en: 'A case-bound formula uses a root not licensed by its formula slot.',
    de: 'Eine im Fall gebundene Formel verwendet einen Eingabeverweis, den ihr Formelplatz nicht erlaubt.',
  },
  'MANTRA-FORMULA-SLOT-USES': {
    en: 'A formula-slot uses declaration is not a valid root-symbol vector.',
    de: 'Die uses-Angabe des Formelplatzes ist kein gültiger Vektor von Eingabesymbolen.',
  },
  'MANTRA-ID-DUPLICATE': {
    en: 'The schema declares an identifier more than once.',
    de: 'Das Schema deklariert eine Kennung mehrfach.',
  },
  'MANTRA-ID-INVALID': {
    en: 'An identifier does not match the supported naming syntax.',
    de: 'Eine Kennung entspricht nicht der unterstützten Namenssyntax.',
  },
  'MANTRA-ID-RESERVED': {
    en: 'An identifier conflicts with a reserved engine or kernel name.',
    de: 'Eine Kennung kollidiert mit einem reservierten Namen des Motors oder Rechenkerns.',
  },
  'MANTRA-ID-SHADOWED': {
    en: 'An application function shadows an existing callable name.',
    de: 'Eine Anwendungsfunktion verdeckt einen bereits vorhandenen Funktionsnamen.',
  },
  'MANTRA-INCLUDE-CYCLE': {
    en: 'Fragments recursively include each other.',
    de: 'Fragmente binden sich gegenseitig im Kreis ein.',
  },
  'MANTRA-INCLUDE-MISSING': {
    en: 'An included document cannot be resolved.',
    de: 'Ein eingebundenes Dokument lässt sich nicht auflösen.',
  },
  'MANTRA-INCLUDE-PATH': {
    en: 'An include form does not supply a string path.',
    de: 'Die include-Deklaration enthält keinen Textpfad.',
  },
  'MANTRA-INCLUDE-ROOT': {
    en: 'An included document is not a fragment.',
    de: 'Ein eingebundenes Dokument ist kein Fragment.',
  },
  'MANTRA-INPUT': {
    en: 'An input declaration is malformed.',
    de: 'Eine Eingabedeklaration ist fehlerhaft.',
  },
  'MANTRA-INPUT-COLUMN': {
    en: 'A table record has an unknown or required nonnumeric missing column.',
    de: 'Ein Tabellenrecord enthält eine unbekannte Spalte oder ihm fehlt eine erforderliche nicht numerische Spalte.',
  },
  'MANTRA-INPUT-COLUMNS': {
    en: 'A table declaration lacks valid scalar column types.',
    de: 'Die Tabellendeklaration enthält keine gültigen skalaren Spaltentypen.',
  },
  'MANTRA-INPUT-MIN-ROWS': {
    en: 'The table has too few rows; an invalid minimum-row declaration uses the same code with structural category.',
    de: 'Die Tabelle enthält zu wenige Zeilen. Eine ungültige Mindestzeilen-Deklaration verwendet denselben Code mit struktureller Kategorie.',
  },
  'MANTRA-INPUT-MISSING': {
    en: 'A nonoptional input cannot bind a missing fact without a supported default.',
    de: 'Eine nicht optionale Eingabe kann eine fehlende Tatsache ohne unterstützten Vorgabewert nicht binden.',
  },
  'MANTRA-INPUT-OPTIONS': {
    en: 'An input options declaration is malformed.',
    de: 'Die Optionen einer Eingabe sind fehlerhaft deklariert.',
  },
  'MANTRA-INPUT-RANGE': {
    en: 'An input number is outside its declared minimum or maximum.',
    de: 'Eine eingegebene Zahl liegt außerhalb ihrer deklarierten Unter- oder Obergrenze.',
  },
  'MANTRA-INPUT-REFERENCE': {
    en: 'A supplied table foreign key refers to an unavailable member.',
    de: 'Ein gelieferter Fremdschlüssel der Tabelle verweist auf ein nicht verfügbares Mitglied.',
  },
  'MANTRA-INPUT-REQUIRED': {
    en: 'An unconditional or applicable conditional requirement lacks an explicitly supplied nonblank fact.',
    de: 'Eine unbedingte oder anwendbare bedingte Pflichtangabe hat keine ausdrücklich gelieferte, nicht leere Tatsache.',
  },
  'MANTRA-INPUT-TYPE': {
    en: 'A supplied input or table-cell value does not match its declared type.',
    de: 'Eine Eingabe oder ein Tabellenzellenwert entspricht nicht dem deklarierten Typ.',
  },
  'MANTRA-LAYOUT-AXES': {
    en: 'Row, column or fixed axes/members are unknown, overlap, or select an incompatible node',
    de: 'Zeilen-, Spalten- oder feste Achsen bzw. Mitglieder sind unbekannt, überschneiden sich oder passen nicht zum ausgewählten Knoten.',
  },
  'MANTRA-LAYOUT-COL': {
    en: 'A layout column declaration is malformed.',
    de: 'Eine Layoutspalte ist fehlerhaft deklariert.',
  },
  'MANTRA-LAYOUT-COLUMNS': {
    en: 'A layout column list or style selection is malformed.',
    de: 'Eine Spaltenliste oder Stilauswahl des Layouts ist fehlerhaft.',
  },
  'MANTRA-LAYOUT-CONTENT': {
    en: 'A layout column requests unsupported content.',
    de: 'Eine Layoutspalte fordert einen nicht unterstützten Inhalt an.',
  },
  'MANTRA-LAYOUT-FIXED': {
    en: 'A fixed layout member must be a keyword key',
    de: 'Ein festes Layoutmitglied muss einen Schlüsselwortschlüssel verwenden.',
  },
  'MANTRA-LAYOUT-FORM': {
    en: 'A layout contains an unsupported declaration form.',
    de: 'Das Layout enthält eine nicht unterstützte Deklarationsform.',
  },
  'MANTRA-LAYOUT-ID': {
    en: 'A layout selector requires a valid item or section identifier.',
    de: 'Ein Layoutselektor benötigt eine gültige Element- oder Abschnittskennung.',
  },
  'MANTRA-LAYOUT-PRESET': {
    en: 'The layout preset name is unknown.',
    de: 'Der Name der Layoutvorlage ist unbekannt.',
  },
  'MANTRA-LAYOUT-ROOT': {
    en: 'The document is not a layout document.',
    de: 'Das Dokument ist kein Layoutdokument.',
  },
  'MANTRA-LAYOUT-ROW-DIMENSION': {
    en: 'A row dimension must be an identifier',
    de: 'Eine Zeilendimension muss eine Kennung sein.',
  },
  'MANTRA-LAYOUT-ROW-NUMBERS': {
    en: 'The row-numbering mode is unsupported.',
    de: 'Der Modus zur Zeilennummerierung wird nicht unterstützt.',
  },
  'MANTRA-LAYOUT-SELECTOR': {
    en: 'A style selector is malformed or uses an unsupported selector key.',
    de: 'Ein Stilselektor ist fehlerhaft oder verwendet einen nicht unterstützten Selektorschlüssel.',
  },
  'MANTRA-LAYOUT-STYLE': {
    en: 'Style declarations contain unsupported values or properties.',
    de: 'Eine Stildeklaration enthält nicht unterstützte Werte oder Eigenschaften.',
  },
  'MANTRA-LAYOUT-STYLE-CLASS': {
    en: 'A named style class is malformed, duplicated or exceeds the local definition limit.',
    de: 'Eine benannte Stilklasse ist fehlerhaft, doppelt definiert oder überschreitet die Grenze für lokale Definitionen.',
  },
  'MANTRA-LAYOUT-STYLE-PRESET': {
    en: 'A style preset selection is unknown, malformed or repeated.',
    de: 'Eine ausgewählte Stilvorlage ist unbekannt, fehlerhaft angegeben oder mehrfach ausgewählt.',
  },
  'MANTRA-LAYOUT-STYLE-USE': {
    en: 'A style declaration explicitly references an unavailable class or an invalid or excessive reference list.',
    de: 'Eine Stildeklaration verweist ausdrücklich auf eine nicht verfügbare Klasse oder enthält eine ungültige oder zu lange Verweisliste.',
  },
  'MANTRA-LAYOUT-TABLE': {
    en: 'A layout table declaration is malformed.',
    de: 'Eine Layouttabelle ist fehlerhaft deklariert.',
  },
  'MANTRA-LINE-ARITY': {
    en: 'A line has unexpected arguments.',
    de: 'Eine Rechenzeile enthält unerwartete Argumente.',
  },
  'MANTRA-LINE-FORMULA': {
    en: 'A line is missing its calculation formula.',
    de: 'Einer Rechenzeile fehlt ihre Berechnungsformel.',
  },
  'MANTRA-LINK-ADDRESS': {
    en: 'A source, target, participating case or full coordinate cannot be resolved unambiguously.',
    de: 'Eine Quelle, ein Ziel, ein beteiligter Fall oder eine vollständige Koordinate lässt sich nicht eindeutig auflösen.',
  },
  'MANTRA-LINK-CONFLICT': {
    en: 'A linked target already has a local, imported or competing linked fact, including explicit nil.',
    de: 'Ein Verknüpfungsziel hat bereits eine lokale, importierte oder konkurrierende verknüpfte Tatsache. Das gilt auch für ausdrücklich geliefertes nil.',
  },
  'MANTRA-LINK-CYCLE': {
    en: 'The canonical source case is already on the active graph path.',
    de: 'Der kanonische Quellfall liegt bereits auf dem aktiven Verknüpfungspfad.',
  },
  'MANTRA-LINK-REVISION': {
    en: 'Requested Explain evidence refers to a changed source revision.',
    de: 'Der angeforderte Rechenweg bezieht sich auf eine inzwischen geänderte Quellenrevision.',
  },
  'MANTRA-LINK-TYPE': {
    en: 'A defined source scalar does not match the target input declaration.',
    de: 'Ein definierter Quellwert entspricht nicht dem deklarierten Typ der Zieleingabe.',
  },
  'MANTRA-LINK-UNDEFINED': {
    en: 'A source failed technically, has a nil/inactive/missing value, or an authored link has not been materialized.',
    de: 'Die Quelle ist technisch fehlgeschlagen, ihr Wert ist nil, inaktiv oder fehlt, oder eine deklarierte Verknüpfung wurde noch nicht aufgelöst.',
  },
  'MANTRA-LINK-VERSION': {
    en: 'The source schema identity and opaque version do not match the exact authored assertion.',
    de: 'Schemaidentität und unveränderte Versionskennung der Quelle stimmen nicht mit der genauen deklarierten Anforderung überein.',
  },
  'MANTRA-MIGRATION-BINDING': {
    en: 'The migration binding contract failed; correct the explicit request or resources before retrying.',
    de: 'Die Bindung der Migration erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-MIGRATION-COMMIT': {
    en: 'The migration commit contract failed; correct the explicit request or resources before retrying.',
    de: 'Das Schreiben der Migration erfüllt seinen Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-MIGRATION-EDIT': {
    en: 'The migration edit contract failed; correct the explicit request or resources before retrying.',
    de: 'Eine Migrationsänderung erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-MIGRATION-LIMIT': {
    en: 'The migration limit contract failed; correct the explicit request or resources before retrying.',
    de: 'Die Migration überschreitet ihre Vertragsgrenze. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-MIGRATION-REVIEW': {
    en: 'The supplied token does not identify the exact reviewed migration preview.',
    de: 'Das gelieferte Token bezeichnet nicht exakt die geprüfte Migrationsvorschau.',
  },
  'MANTRA-MIGRATION-STALE': {
    en: 'Case bytes, the source graph or target resources changed after the reviewed preview.',
    de: 'Die Fallbytes, der Quellgraph oder die Zielressourcen haben sich seit der geprüften Vorschau geändert.',
  },
  'MANTRA-MIGRATION-STORE': {
    en: 'The migration store contract failed; correct the explicit request or resources before retrying.',
    de: 'Der Speichervertrag der Migration ist verletzt. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-MIGRATION-TECHNICAL': {
    en: 'The target evaluation has a technical failure; no migration is committed.',
    de: 'Die Zielberechnung hat einen technischen Fehler. Es wird keine Migration gespeichert.',
  },
  'MANTRA-NOTE-TEXT': {
    en: 'A note does not supply a text literal.',
    de: 'Eine Notiz enthält kein Textliteral.',
  },
  'MANTRA-PACKAGE-BINDING': {
    en: 'The package binding contract failed; correct the explicit request or resources before retrying.',
    de: 'Eine Paketbindung erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-CONTAINER': {
    en: 'The package container contract failed; correct the explicit request or resources before retrying.',
    de: 'Der Paketcontainer erfüllt seinen Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-DATE': {
    en: 'The package date contract failed; correct the explicit request or resources before retrying.',
    de: 'Das Paketdatum erfüllt seinen Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-DATE-GAP': {
    en: 'No declared candidate provides a required parameter key at the explicit effective date.',
    de: 'Kein deklarierter Kandidat liefert einen erforderlichen Parameterschlüssel zum ausdrücklich angegebenen Gültigkeitsdatum.',
  },
  'MANTRA-PACKAGE-DATE-OVERLAP': {
    en: 'More than one declared candidate provides the same required key at the effective date.',
    de: 'Mehrere deklarierte Kandidaten liefern denselben erforderlichen Schlüssel zum Gültigkeitsdatum.',
  },
  'MANTRA-PACKAGE-DEPENDENCY': {
    en: 'The package dependency contract failed; correct the explicit request or resources before retrying.',
    de: 'Eine Paketabhängigkeit erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-DUPLICATE': {
    en: 'The package duplicate contract failed; correct the explicit request or resources before retrying.',
    de: 'Das Paket enthält eine vertragswidrige doppelte Deklaration. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-ENGINE': {
    en: 'The package engine comparator intersection excludes the supplied engine version.',
    de: 'Die Schnittmenge der Versionsbedingungen des Pakets schließt die angegebene Motorversion aus.',
  },
  'MANTRA-PACKAGE-HOST-BINDING': {
    en: 'The writable host case has an invalid or stale package binding; review its explicit package identity and source revision.',
    de: 'Der beschreibbare Host-Fall hat eine ungültige oder veraltete Paketbindung. Prüfen Sie die ausdrücklich gewählte Paketidentität und Quellrevision.',
  },
  'MANTRA-PACKAGE-IMPORT': {
    en: 'A captured-data importer is missing or changed the source-owned case or link identity.',
    de: 'Ein Importer für erfasste Daten fehlt oder hat die ursprüngliche Fall- oder Verknüpfungsidentität verändert.',
  },
  'MANTRA-PACKAGE-INTEGRITY': {
    en: 'Captured resource bytes do not match the manifest length or SHA-256 digest.',
    de: 'Die erfassten Ressourcenbytes stimmen nicht mit der Länge oder dem SHA-256-Prüfwert des Manifests überein.',
  },
  'MANTRA-PACKAGE-LIMIT': {
    en: 'The package limit contract failed; correct the explicit request or resources before retrying.',
    de: 'Das Paket überschreitet eine Vertragsgrenze. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-MANIFEST': {
    en: 'The package manifest contract failed; correct the explicit request or resources before retrying.',
    de: 'Das Paketmanifest erfüllt seinen Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-PARAMETERS': {
    en: 'The package parameters contract failed; correct the explicit request or resources before retrying.',
    de: 'Die Paketparameter erfüllen ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-PATH': {
    en: 'A resource path is noncanonical, escapes its container or follows a prohibited symbolic link.',
    de: 'Ein Ressourcenpfad ist nicht kanonisch, verlässt seinen Container oder folgt einer nicht erlaubten symbolischen Verknüpfung.',
  },
  'MANTRA-PACKAGE-RANGE': {
    en: 'The package range contract failed; correct the explicit request or resources before retrying.',
    de: 'Ein Versionsbereich des Pakets erfüllt seinen Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-RESOURCE': {
    en: 'The package resource contract failed; correct the explicit request or resources before retrying.',
    de: 'Eine Paketressource erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-ROLE': {
    en: 'The package role contract failed; correct the explicit request or resources before retrying.',
    de: 'Eine Ressourcenrolle des Pakets erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PACKAGE-SECURE-READ': {
    en: 'The selected filesystem cannot provide the explicitly required directory-relative access capability.',
    de: 'Das ausgewählte Dateisystem stellt die ausdrücklich verlangte Fähigkeit zum verzeichnisbezogenen Zugriff nicht bereit.',
  },
  'MANTRA-PACKAGE-SOURCE-CHANGED': {
    en: 'A cooperative directory capture detected a file or directory change before the capture completed.',
    de: 'Während der kooperativen Verzeichniserfassung wurde eine Änderung an einer Datei oder einem Verzeichnis erkannt.',
  },
  'MANTRA-PACKAGE-UNLISTED': {
    en: 'A requested resource is not declared in the captured manifest.',
    de: 'Eine angeforderte Ressource ist im erfassten Manifest nicht deklariert.',
  },
  'MANTRA-PACKAGE-UNREGISTERED': {
    en: 'A referenced package has not been explicitly mounted by the host.',
    de: 'Ein referenziertes Paket wurde vom Host nicht ausdrücklich eingebunden.',
  },
  'MANTRA-PACKAGE-UTF8': {
    en: 'A captured resource is not valid UTF-8.',
    de: 'Eine erfasste Ressource ist kein gültiges UTF-8.',
  },
  'MANTRA-PACKAGE-VERSION': {
    en: 'The package version contract failed; correct the explicit request or resources before retrying.',
    de: 'Die Paketversion erfüllt ihren Vertrag nicht. Korrigieren Sie Anfrage oder Ressourcen vor einem erneuten Versuch.',
  },
  'MANTRA-PARAM': {
    en: 'A schema parameter declaration is malformed.',
    de: 'Ein Schemaparameter ist fehlerhaft deklariert.',
  },
  'MANTRA-PARAMETERS-DUPLICATE': {
    en: 'A parameter-set document assigns a parameter twice.',
    de: 'Ein Parametersatz weist einem Parameter mehrfach einen Wert zu.',
  },
  'MANTRA-PARAMETERS-FORM': {
    en: 'A parameter-set document contains an unsupported form.',
    de: 'Ein Parametersatz enthält eine nicht unterstützte Form.',
  },
  'MANTRA-PARAMETERS-ID': {
    en: 'The parameter-set identifier is missing.',
    de: 'Die Kennung des Parametersatzes fehlt.',
  },
  'MANTRA-PARAMETERS-ROOT': {
    en: 'The document is not a parameter-set document.',
    de: 'Das Dokument ist kein Parametersatz.',
  },
  'MANTRA-PARAMETERS-SCHEMA': {
    en: 'A parameter set targets a different schema.',
    de: 'Ein Parametersatz richtet sich an ein anderes Schema.',
  },
  'MANTRA-PARAMETERS-UNKNOWN': {
    en: 'A parameter set assigns an undeclared parameter.',
    de: 'Ein Parametersatz weist einem nicht deklarierten Parameter einen Wert zu.',
  },
  'MANTRA-PARAMETERS-VALUE': {
    en: 'A parameter-set value declaration is malformed.',
    de: 'Eine Wertdeklaration des Parametersatzes ist fehlerhaft.',
  },
  'MANTRA-PDF-GLYPH': {
    en: 'A text glyph cannot be encoded by the embedded font; export fails explicitly.',
    de: 'Ein Textzeichen lässt sich mit der eingebetteten Schrift nicht darstellen. Der Export schlägt ausdrücklich fehl.',
  },
  'MANTRA-PDF-LIMIT': {
    en: 'PDF row, page, column, header or byte capacity was exceeded; no incomplete PDF is returned.',
    de: 'Die Kapazität für PDF-Zeilen, Seiten, Spalten, Kopfzeilen oder Bytes wurde überschritten. Es wird kein unvollständiges PDF zurückgegeben.',
  },
  'MANTRA-PERIOD-CONTINUITY': {
    en: 'Declared periods are out of order, overlap or leave a gap',
    de: 'Die deklarierten Perioden sind falsch geordnet, überlappen sich oder lassen eine Lücke.',
  },
  'MANTRA-PERIOD-COUNT': {
    en: 'A period dimension needs at least one period',
    de: 'Eine Periodendimension benötigt mindestens eine Periode.',
  },
  'MANTRA-PERIOD-DECLARATION': {
    en: 'Static/generated period syntax or options are invalid',
    de: 'Die Syntax oder Optionen für feste oder erzeugte Perioden sind ungültig.',
  },
  'MANTRA-PERIOD-KEY': {
    en: 'Period keys must be nonblank and unique',
    de: 'Periodenschlüssel müssen nicht leer und eindeutig sein.',
  },
  'MANTRA-PERIOD-LIMIT': {
    en: 'Period member count exceeds the collection ceiling',
    de: 'Die Anzahl der Periodenmitglieder überschreitet die Obergrenze für Sammlungen.',
  },
  'MANTRA-PERIOD-PARENT': {
    en: 'A child period is outside or crosses the parent interval',
    de: 'Eine untergeordnete Periode liegt außerhalb des übergeordneten Intervalls oder überschreitet dessen Grenze.',
  },
  'MANTRA-PERIOD-RANGE': {
    en: 'A period start must precede its exclusive end',
    de: 'Der Periodenbeginn muss vor dem Enddatum liegen; das Enddatum gehört nicht zur Periode.',
  },
  'MANTRA-READ-DUPLICATE-KEY': {
    en: 'An options map contains duplicate keys.',
    de: 'Eine Optionszuordnung enthält doppelte Schlüssel.',
  },
  'MANTRA-READ-LITERAL': {
    en: 'A literal violates kernel value-construction limits or literal rules.',
    de: 'Ein Literal verletzt die Größenbegrenzungen oder Konstruktionsregeln des Rechenkerns.',
  },
  'MANTRA-READ-MAP': {
    en: 'A map literal has an odd number of key/value forms.',
    de: 'Ein Zuordnungsliteral enthält eine ungerade Anzahl von Schlüssel- und Wertformen.',
  },
  'MANTRA-READ-OPTIONS': {
    en: 'A declaration expected an options map.',
    de: 'Eine Deklaration erwartet eine Optionszuordnung.',
  },
  'MANTRA-READ-SYNTAX': {
    en: 'The document cannot be parsed as valid DSL syntax.',
    de: 'Das Dokument lässt sich nicht als gültige DSL-Syntax analysieren.',
  },
  'MANTRA-RECONCILE-FAILED': {
    en: 'The absolute left-minus-right difference exceeds the declared inclusive tolerance.',
    de: 'Der absolute Unterschied zwischen linker und rechter Seite überschreitet die deklarierte inklusive Toleranz.',
  },
  'MANTRA-RECONCILE-TOLERANCE': {
    en: 'A reconciliation tolerance is negative, nonnumeric or not a literal.',
    de: 'Die Abstimmungstoleranz ist negativ, nicht numerisch oder kein Literal.',
  },
  'MANTRA-RECORD': {
    en: 'An input or member record cannot be bound to the kernel record type.',
    de: 'Ein Eingabe- oder Mitgliedsrecord lässt sich nicht an den Recordtyp des Rechenkerns binden.',
  },
  'MANTRA-RESULT-TYPE': {
    en: 'A numeric formula produced an incompatible value.',
    de: 'Eine numerische Formel hat einen nicht kompatiblen Wert geliefert.',
  },
  'MANTRA-RUN-CANCELLED': {
    en: 'Shared cancellation stopped the current run; any partial graph is reported without an old root result.',
    de: 'Ein gemeinsames Abbruchsignal hat den aktuellen Lauf beendet. Ein unvollständiger Verknüpfungsgraph wird ohne ein altes Wurzelergebnis gemeldet.',
  },
  'MANTRA-RUN-DEADLINE': {
    en: 'The shared effective run deadline was reached.',
    de: 'Die gemeinsam geltende Frist für den Berechnungslauf wurde erreicht.',
  },
  'MANTRA-RUN-LIMIT': {
    en: 'A cumulative counter or high-water bound was exceeded; typed usage includes counter, limit and attempted amount.',
    de: 'Ein kumulativer Zähler oder eine obere Grenze wurde überschritten. Die Nutzungsdaten enthalten Zähler, Grenze und versuchten Umfang.',
  },
  'MANTRA-SCHEMA-CLASS': {
    en: 'A presentation class declaration is malformed.',
    de: 'Eine Darstellungsklasse ist fehlerhaft deklariert.',
  },
  'MANTRA-SCHEMA-DISPLAY': {
    en: 'A section display mode is unsupported.',
    de: 'Der Anzeigemodus eines Abschnitts wird nicht unterstützt.',
  },
  'MANTRA-SCHEMA-FORM': {
    en: 'A schema contains an unsupported declaration form.',
    de: 'Das Schema enthält eine nicht unterstützte Deklarationsform.',
  },
  'MANTRA-SCHEMA-GROUP': {
    en: 'An input group is not a keyword.',
    de: 'Eine Eingabegruppe ist kein Schlüsselwort.',
  },
  'MANTRA-SCHEMA-GROUP-TITLES': {
    en: 'Input-group titles are not a valid text-label map.',
    de: 'Die Titel der Eingabegruppen bilden keine gültige Zuordnung von Schlüsseln zu Texten.',
  },
  'MANTRA-SCHEMA-HEADLINE': {
    en: 'The schema headline does not identify a valid node.',
    de: 'Das hervorgehobene Ergebnis des Schemas verweist auf keinen gültigen Knoten.',
  },
  'MANTRA-SCHEMA-ID': {
    en: 'The schema identifier is missing.',
    de: 'Die Kennung des Schemas fehlt.',
  },
  'MANTRA-SCHEMA-LABEL': {
    en: 'A schema item lacks a valid label.',
    de: 'Ein Schemaelement hat keine gültige Bezeichnung.',
  },
  'MANTRA-SCHEMA-LAYOUT': {
    en: 'The schema layout selection is malformed.',
    de: 'Die Layoutauswahl des Schemas ist fehlerhaft.',
  },
  'MANTRA-SCHEMA-OP': {
    en: 'A contribution operator is outside plus, minus or info.',
    de: 'Ein Beitragsoperator ist weder plus, minus noch info.',
  },
  'MANTRA-SCHEMA-PER': {
    en: 'An item dimension declaration is not a symbol or symbol vector.',
    de: 'Die Dimensionsangabe eines Elements ist weder ein Symbol noch ein Symbolvektor.',
  },
  'MANTRA-SCHEMA-PLACEMENT': {
    en: 'A declaration appears in a context where it is not allowed.',
    de: 'Eine Deklaration steht in einem Kontext, in dem sie nicht erlaubt ist.',
  },
  'MANTRA-SCHEMA-PRESENTATION': {
    en: 'A presentation attribute has the wrong value type.',
    de: 'Ein Darstellungsattribut hat einen Wert des falschen Typs.',
  },
  'MANTRA-SCHEMA-ROOT': {
    en: 'The document is not a schema document.',
    de: 'Das Dokument ist kein Schemadokument.',
  },
  'MANTRA-SCHEMA-ROUND': {
    en: 'The rounding scale or mode is malformed.',
    de: 'Die Rundungspräzision oder der Rundungsmodus ist fehlerhaft.',
  },
  'MANTRA-SCHEMA-SIGN-LABELS': {
    en: 'Positive, negative or zero display labels are malformed.',
    de: 'Die Bezeichnungen für positive, negative oder Nullwerte sind fehlerhaft.',
  },
  'MANTRA-SCHEMA-TYPE': {
    en: 'A declared value type is unknown.',
    de: 'Ein deklarierter Werttyp ist unbekannt.',
  },
  'MANTRA-SCHEMA-VERSION': {
    en: 'Schema version must be nonblank literal text.',
    de: 'Die Schemaversion muss ein nicht leeres Textliteral sein.',
  },
  'MANTRA-SLOT-CONTENT': {
    en: 'An extension slot contains an unsupported item.',
    de: 'Ein Erweiterungsplatz enthält ein nicht unterstütztes Element.',
  },
  'MANTRA-SPREAD-DIMS': {
    en: 'An allocation-spread line has an invalid dimension context.',
    de: 'Eine Verteilungszeile hat einen ungültigen Dimensionskontext.',
  },
  'MANTRA-TOTAL-DIMS': {
    en: 'A total combines incompatible dimension contexts.',
    de: 'Eine Summe verbindet nicht kompatible Dimensionskontexte.',
  },
  'MANTRA-TOTAL-TRAILING': {
    en: 'An unterminated contributing sequence has no appropriate total.',
    de: 'Eine nicht abgeschlossene Folge von Beiträgen hat keine passende Summe.',
  },
  'MANTRA-TYPES': {
    en: 'Internal record-type definitions are inconsistent.',
    de: 'Die internen Recordtypdefinitionen sind widersprüchlich.',
  },
  'MANTRA-VALUE': {
    en: 'A formula result violates the kernel value-construction contract.',
    de: 'Ein Formelergebnis verletzt den Wertkonstruktionsvertrag des Rechenkerns.',
  },
  'MANTRA-VALUE-LIMIT': {
    en: 'A formula result exceeds a kernel value-size limit.',
    de: 'Ein Formelergebnis überschreitet eine Größenbegrenzung für Werte des Rechenkerns.',
  },
  'MANTRA-WORKBENCH-BUSY': {
    en: 'The workbench has reached its concurrent event-stream limit.',
    de: 'Die Arbeitsumgebung hat ihre Grenze für gleichzeitige Ereignisströme erreicht.',
  },
  'MANTRA-WORKBENCH-CASE-SCHEMA': {
    en: 'A workspace case does not select a schema.',
    de: 'Ein Arbeitsbereichsfall wählt kein Schema aus.',
  },
  'MANTRA-WORKBENCH-CONFLICT': {
    en: 'An edit revision is stale or the workspace changed before commit.',
    de: 'Die Bearbeitungsrevision ist veraltet oder der Arbeitsbereich hat sich vor dem Speichern geändert.',
  },
  'MANTRA-WORKBENCH-DOCUMENT': {
    en: 'A requested edit or document was rejected by its contract.',
    de: 'Eine angeforderte Bearbeitung oder ein Dokument wurde aufgrund seines Vertrags abgelehnt.',
  },
  'MANTRA-WORKBENCH-EDIT': {
    en: 'Editor text, input coordinates or authoring operations are invalid.',
    de: 'Der Editortext, die Eingabekoordinaten oder eine Autorenaktion ist ungültig.',
  },
  'MANTRA-WORKBENCH-HOST': {
    en: 'The request host is outside the permitted loopback host.',
    de: 'Der Anfragehost liegt außerhalb des erlaubten lokalen Loopback-Hosts.',
  },
  'MANTRA-WORKBENCH-INTERNAL': {
    en: 'The workbench request failed unexpectedly.',
    de: 'Die Anfrage an die Arbeitsumgebung ist unerwartet fehlgeschlagen.',
  },
  'MANTRA-WORKBENCH-NOT-FOUND': {
    en: 'The requested workspace item or route is absent.',
    de: 'Das angeforderte Element oder die Route fehlt im Arbeitsbereich.',
  },
  'MANTRA-WORKBENCH-REQUEST': {
    en: 'The request method, route parameters or payload is invalid.',
    de: 'Die Anfragemethode, Routenparameter oder Nutzdaten sind ungültig.',
  },
  'MANTRA-WORKBENCH-TOKEN': {
    en: 'The required local-session token is missing or invalid.',
    de: 'Das erforderliche Token der lokalen Sitzung fehlt oder ist ungültig.',
  },
  'MANTRA-WORKBENCH-TOO-LARGE': {
    en: 'A request, workbook or output exceeds its configured size limit.',
    de: 'Eine Anfrage, Arbeitsmappe oder Ausgabe überschreitet ihre konfigurierte Größenbegrenzung.',
  },
  'MANTRA-WORKBENCH-UNAVAILABLE': {
    en: 'The requested endpoint is unavailable.',
    de: 'Der angeforderte Endpunkt ist nicht verfügbar.',
  },
}

export const diagnosticMessages: Readonly<Record<string, Readonly<Record<Language, string>>>> = Object.freeze(
  Object.fromEntries(Object.entries(catalog).map(([code, messages]) => [code, Object.freeze(messages)])),
)

export type DiagnosticExplanation = {
  summary: string
  details: string
  source: 'catalogue' | 'kernel' | 'unknown'
}

/** Unknown kernel causes have an explicit fallback, without guessing their category or meaning. */
export function explainDiagnostic(
  diagnostic: Pick<Diagnostic, 'code' | 'message'>,
  lang: Language,
): DiagnosticExplanation {
  if (Object.hasOwn(diagnosticMessages, diagnostic.code))
    return { summary: diagnosticMessages[diagnostic.code][lang], details: diagnostic.message, source: 'catalogue' }
  const kernel = diagnostic.code.startsWith('DSL-')
  const summary = kernel
    ? lang === 'de'
      ? 'Der Rechenkern hat einen Befund gemeldet. Die Originaldetails enthalten die konkrete Ursache.'
      : 'The calculation kernel reported a diagnostic. The original details contain its specific cause.'
    : lang === 'de'
      ? 'Für diesen Befund liegt noch keine übersetzte Erklärung vor. Prüfen Sie die Originaldetails.'
      : 'No translated explanation is available for this diagnostic. Review the original details.'
  return { summary, details: diagnostic.message, source: kernel ? 'kernel' : 'unknown' }
}

export function diagnosticDetailsLabel(lang: Language): string {
  return lang === 'de' ? 'Originaldetails' : 'Original details'
}
