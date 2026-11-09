"""Realistic French PDFs the way people actually make them: browser/WeasyPrint reports (justified,
cards, two columns, formulas, SVG charts), Word documents exported by LibreOffice (first-line
indents, numbered headings, tables, images, headers/footers), slide decks exported to PDF, and
worksheets with drawings and labelled diagrams.  gen.py <outDir> [variantsPerKind]"""
import json, random, sys, os, io, subprocess, tempfile, html
import weasyprint
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT = sys.argv[1]; N = int(sys.argv[2]) if len(sys.argv) > 2 else 6
os.makedirs(OUT, exist_ok=True)
HERE = os.path.dirname(os.path.abspath(__file__))
WIKI = [p["text"] for p in json.load(open("/opt/data/text/wiki_fr.json")) if len(p["text"]) > 250]
SCHOOL = [l.strip() for l in open(os.path.join(HERE, "..", "corpus", "school_fr.txt")) if l.strip()]
TITLES = ["La photosynthèse et le cycle du carbone", "La Révolution française (1789-1799)", "Les fonctions affines et leurs représentations",
          "Le contrôle de gestion dans les PME", "La cellule : structure et fonctionnement", "Introduction à la statistique descriptive",
          "Les énergies renouvelables en Europe", "Le marché du travail et le chômage", "La tectonique des plaques", "Les suites numériques"]
H2 = ["Définitions et notions clés", "Contexte historique", "Méthode de calcul", "Analyse des résultats", "Exemples d'application",
      "Limites du modèle", "Synthèse", "Exercices corrigés", "Étude de cas", "Points de vigilance"]
FORMULAS = ["x̄ = (1/n) · Σ xᵢ", "σ² = (1/n) · Σ (xᵢ − x̄)²", "f(x) = ax + b", "Δ = b² − 4ac", "E = mc²", "uₙ₊₁ = uₙ + r",
            "P(A ∩ B) = P(A) × P(B)", "6CO₂ + 6H₂O → C₆H₁₂O₆ + 6O₂", "v = d / t", "TVA = prix HT × 20 %"]
LIST_LEADS = ["Premièrement", "Ensuite", "Par ailleurs", "Enfin", "En pratique", "À retenir"]

def para(r, k=1): return " ".join(r.choice(WIKI) for _ in range(k))
def esc(t): return html.escape(t)
def boldify(r, t):
    w = t.split(" ")
    if len(w) > 12:
        i = r.randrange(0, len(w) - 4); j = i + r.randint(1, 3)
        w[i] = "<b>" + w[i]; w[j] = w[j] + "</b>"
        if r.random() < .5:
            k = r.randrange(0, len(w) - 2)
            if k > j or k + 1 < i: w[k] = "<i>" + w[k]; w[k + 1] = w[k + 1] + "</i>"
    return " ".join(w)

def chart_svg(r, fr=True):
    fig, ax = plt.subplots(figsize=(5.2, 2.6))
    cats = r.sample(["Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet", "Août"], 5)
    ax.bar(cats, [r.randint(10, 90) for _ in cats], color="#3b6ea5")
    ax.set_title(r.choice(["Évolution des ventes mensuelles", "Nombre d'élèves par classe", "Températures moyennes relevées"]))
    ax.set_ylabel(r.choice(["Chiffre d'affaires (k€)", "Effectif", "Température (°C)"]))
    plt.rcParams["svg.fonttype"] = "none"
    b = io.StringIO(); fig.savefig(b, format="svg", bbox_inches="tight"); plt.close(fig)
    s = b.getvalue(); return s[s.index("<svg"):]

def chart_png(r, path):
    fig, ax = plt.subplots(figsize=(5, 2.8)); xs = list(range(10)); ax.plot(xs, [r.random() * 10 + x for x in xs], marker="o")
    ax.set_title("Courbe expérimentale"); ax.set_xlabel("Temps (s)"); ax.set_ylabel("Vitesse (m/s)"); fig.savefig(path, dpi=110, bbox_inches="tight"); plt.close(fig)

CSS_REPORT = """@page { size: A4; margin: 2cm 1.8cm; @bottom-center { content: "Page " counter(page) " sur " counter(pages); font-size: 8.5pt; color: #666; } }
body { font-family: %(font)s; font-size: %(size)spt; line-height: %(lh)s; color: #222; text-align: %(align)s; }
h1 { font-size: 20pt; color: %(accent)s; margin: 0 0 10px; text-align: left; } h2 { font-size: 13pt; color: %(accent)s; border-bottom: 1px solid #ccc; padding-bottom: 2px; margin: 14px 0 6px; text-align: left; }
h3 { font-size: 11pt; font-style: italic; margin: 10px 0 4px; } p { margin: 0 0 7px; %(indent)s } li { margin-bottom: 3px; }
.box { border-left: 4px solid %(accent)s; background: #f5f5f5; padding: 8px 12px; margin: 8px 0; }
.formula { text-align: center; font-family: "DejaVu Serif", serif; font-style: italic; font-size: 12pt; margin: 8px 0; }
table { border-collapse: collapse; width: 100%%; font-size: 9pt; margin: 8px 0; text-align: left; } th, td { border: 1px solid #aaa; padding: 3px 5px; } th { background: %(thbg)s; color: #fff; }
figure { text-align: center; margin: 8px 0; } figcaption { font-size: 8.5pt; color: #555; font-style: italic; }
.cols { column-count: 2; column-gap: 18px; } .meta { color: #666; font-size: 9pt; margin-bottom: 12px; }"""

def report(r, i):
    font = r.choice(['"Helvetica", sans-serif', '"Times New Roman", serif', '"DejaVu Sans", sans-serif', '"Carlito", "Calibri", sans-serif'])
    css = CSS_REPORT % dict(font=font, size=r.choice([10, 10.5, 11, 12]), lh=r.choice([1.25, 1.4, 1.5]), align=r.choice(["justify", "justify", "left"]),
                            accent=r.choice(["#1f4e79", "#8b1e3f", "#2e7d32", "#c9a227"]), thbg=r.choice(["#1f4e79", "#444"]),
                            indent=r.choice(["", "text-indent: 1.2em;"]))
    title = r.choice(TITLES); body = [f"<h1>{esc(title)}</h1><div class='meta'>Cours de {r.choice(['Mme Durand', 'M. Martin', 'Mme Leroy'])} — Classe de {r.choice(['Seconde', 'Première', 'Terminale', 'BTS'])}</div>"]
    cols = r.random() < .35
    if cols: body.append("<div class='cols'>")
    for s in range(r.randint(3, 5)):
        body.append(f"<h2>{s + 1}. {esc(r.choice(H2))}</h2>")
        for _ in range(r.randint(1, 3)): body.append(f"<p>{boldify(r, esc(para(r, r.randint(1, 3))))}</p>")
        x = r.random()
        if x < .25: body.append("<ul>" + "".join(f"<li><b>{l} :</b> {esc(r.choice(SCHOOL))}</li>" for l in r.sample(LIST_LEADS, 3)) + "</ul>")
        elif x < .4: body.append(f"<div class='formula'>{esc(r.choice(FORMULAS))}</div><p>{esc(para(r))}</p>")
        elif x < .55: body.append(f"<div class='box'><b>À retenir :</b> {esc(r.choice(SCHOOL))} {esc(para(r))}</div>")
        elif x < .7:
            rows = "".join(f"<tr><td>{esc(r.choice(['Paris', 'Lyon', 'Marseille', 'Lille', 'Nantes']))}</td><td>{r.randint(10, 99)},{r.randint(0, 9)} %</td><td>{esc(r.choice(['en hausse', 'stable', 'en baisse légère']))}</td></tr>" for _ in range(r.randint(3, 5)))
            body.append(f"<table><tr><th>Ville</th><th>Taux</th><th>Tendance</th></tr>{rows}</table>")
        elif x < .85 and not cols: body.append(f"<figure>{chart_svg(r)}<figcaption>Figure {s + 1} : {esc(r.choice(['Répartition par mois', 'Résultats observés', 'Données de l’enquête']))}</figcaption></figure>")
        else: body.append("<ol>" + "".join(f"<li>{esc(r.choice(SCHOOL))}</li>" for _ in range(3)) + "</ol>")
    if cols: body.append("</div>")
    doc = f"<!doctype html><html lang='fr'><head><meta charset='utf-8'><title>{esc(title)}</title><style>{css}</style></head><body>{''.join(body)}</body></html>"
    weasyprint.HTML(string=doc).write_pdf(os.path.join(OUT, f"report_{i:02d}.pdf"))

def word(r, i, tmp):
    from docx import Document
    from docx.shared import Pt, Cm, RGBColor
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    d = Document(); st = d.styles["Normal"]; st.font.name = r.choice(["Calibri", "Times New Roman", "Arial", "Cambria"]); st.font.size = Pt(r.choice([11, 12]))
    sec = d.sections[0]; sec.header.paragraphs[0].text = f"Lycée Jean Moulin — {r.choice(TITLES)}"; sec.footer.paragraphs[0].text = "Document de travail — ne pas diffuser"
    d.add_heading(r.choice(TITLES), 0)
    for s in range(r.randint(3, 5)):
        d.add_heading(f"{['I', 'II', 'III', 'IV', 'V'][s]}. {r.choice(H2)}", 1)
        for _ in range(r.randint(1, 3)):
            p = d.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY if r.random() < .7 else WD_ALIGN_PARAGRAPH.LEFT
            if r.random() < .5: p.paragraph_format.first_line_indent = Cm(1)
            t = para(r, r.randint(1, 2)); w = t.split(" "); k = r.randrange(len(w) // 2)
            p.add_run(" ".join(w[:k]) + " "); b = p.add_run(" ".join(w[k:k + 3]) + " "); b.bold = True
            if r.random() < .4: b.font.color.rgb = RGBColor(0xC0, 0x1F, 0x1F)
            p.add_run(" ".join(w[k + 3:]))
        x = r.random()
        if x < .3:
            for l in r.sample(SCHOOL, 3): d.add_paragraph(l, style=r.choice(["List Bullet", "List Number"]))
        elif x < .55:
            t = d.add_table(rows=4, cols=3); t.style = "Table Grid"
            for c, h in enumerate(["Notion", "Définition", "Exemple"]): t.cell(0, c).text = h
            for rr in range(1, 4):
                for c in range(3): t.cell(rr, c).text = r.choice(SCHOOL)[:60]
        elif x < .75:
            png = os.path.join(tmp, f"c{i}_{s}.png"); chart_png(r, png); d.add_picture(png, width=Cm(12))
            cap = d.add_paragraph(f"Figure {s + 1} : courbe de vitesse mesurée en travaux pratiques"); cap.alignment = WD_ALIGN_PARAGRAPH.CENTER; cap.runs[0].italic = True
        else:
            p = d.add_paragraph(r.choice(FORMULAS)); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; p.runs[0].italic = True
    src = os.path.join(tmp, f"word_{i:02d}.docx"); d.save(src)
    subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", OUT, src], check=True, capture_output=True, timeout=180)

def slides(r, i, tmp):
    from pptx import Presentation
    from pptx.util import Inches, Pt
    p = Presentation(); p.slide_width = Inches(13.33); p.slide_height = Inches(7.5)
    s = p.slides.add_slide(p.slide_layouts[0]); s.shapes.title.text = r.choice(TITLES); s.placeholders[1].text = "Présentation réalisée par le groupe 3 — année scolaire 2025-2026"
    for k in range(r.randint(3, 5)):
        s = p.slides.add_slide(p.slide_layouts[1]); s.shapes.title.text = r.choice(H2)
        tf = s.placeholders[1].text_frame; tf.text = r.choice(SCHOOL)
        for l in r.sample(SCHOOL, r.randint(2, 4)):
            q = tf.add_paragraph(); q.text = l; q.level = r.choice([0, 0, 1])
        if r.random() < .5:
            png = os.path.join(tmp, f"s{i}_{k}.png"); chart_png(r, png); s.shapes.add_picture(png, Inches(8.4), Inches(4.6), width=Inches(4.5))
    src = os.path.join(tmp, f"slides_{i:02d}.pptx"); p.save(src)
    subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", OUT, src], check=True, capture_output=True, timeout=180)

def worksheet(r, i):
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    fd = "/home/user/deepl/app/src/main/assets/fonts/"
    pdfmetrics.registerFont(TTFont("DV", fd + "DejaVuSans.ttf")); pdfmetrics.registerFont(TTFont("LS", fd + "LiberationSans-Regular.ttf")); pdfmetrics.registerFont(TTFont("LSB", fd + "LiberationSans-Bold.ttf"))
    c = canvas.Canvas(os.path.join(OUT, f"worksheet_{i:02d}.pdf"), pagesize=A4); W, H = A4
    c.setFont("LSB", 16); c.drawString(50, H - 60, f"Fiche d'exercices — {r.choice(TITLES)}")
    c.setFont("LS", 10); c.drawString(50, H - 78, "Nom : ....................................   Prénom : ...............................   Classe : ........")
    y = H - 110
    for k in range(4):
        c.setFont("LSB", 11.5); c.setFillColorRGB(.12, .3, .5); c.drawString(50, y, f"Exercice {k + 1}"); c.setFillColorRGB(0, 0, 0); y -= 16
        c.setFont("LS", 10.5)
        for ln in wrap(r.choice(SCHOOL) + " " + r.choice(SCHOOL), 95): c.drawString(50, y, ln); y -= 13.5
        c.setFont("DV", 11); c.drawCentredString(W / 2, y - 4, r.choice(FORMULAS)); y -= 24
        if k == 1:  # labelled diagram
            c.rect(70, y - 110, 160, 100); c.circle(150, y - 60, 30); c.line(230, y - 60, 300, y - 60)
            c.setFont("LS", 9); c.drawString(305, y - 63, "noyau de la cellule"); c.drawString(75, y - 22, "membrane plasmique"); c.drawString(240, y - 100, "cytoplasme")
            y -= 125
        c.setFont("LS", 10.5)
        for q in "abc":
            for ln in wrap(f"{q}) " + r.choice(SCHOOL), 95): c.drawString(62, y, ln); y -= 13.5
        y -= 10
        if y < 160: c.showPage(); y = H - 60
    c.save()

def wrap(t, n):
    out, cur = [], ""
    for w in t.split():
        if len(cur) + len(w) + 1 > n: out.append(cur); cur = w
        else: cur = (cur + " " + w).strip()
    return out + [cur] if cur else out

with tempfile.TemporaryDirectory() as tmp:
    for i in range(N):
        r = random.Random(1000 + i)
        report(r, i); worksheet(r, i)
        word(r, i, tmp)
        if i < max(2, N // 2): slides(r, i, tmp)
print("done", len(os.listdir(OUT)))
