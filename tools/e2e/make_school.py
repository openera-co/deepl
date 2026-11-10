"""Makes school-style French documents like the ones students translate: a lesson (Word and its PDF
export, as teachers share them), slides and a worksheet spreadsheet. usage: make_school.py <outDir>"""
import os, subprocess, sys
from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from pptx import Presentation
from pptx.util import Pt as PPt, Inches
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment
from PIL import Image, ImageDraw, ImageFont

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# A labelled diagram (images must come through untouched).
img = Image.new("RGB", (900, 420), "white"); d = ImageDraw.Draw(img); f = ImageFont.truetype(FONT, 26)
d.ellipse((80, 80, 380, 340), outline=(20, 110, 40), width=6); d.text((150, 190), "Chloroplaste", fill=(20, 110, 40), font=f)
d.line((380, 210, 560, 210), fill="black", width=4); d.polygon([(560, 195), (590, 210), (560, 225)], fill="black")
d.rectangle((600, 150, 860, 270), outline=(200, 120, 0), width=5); d.text((625, 192), "Glucose + O₂", fill=(200, 120, 0), font=f)
img.save(os.path.join(out, "_diagram.png"))

TITLE = RGBColor(0x1F, 0x4E, 0x99); RED = RGBColor(0xC0, 0x20, 0x20)
doc = Document()
st = doc.styles["Normal"]; st.font.name = "Calibri"; st.font.size = Pt(11)
h = doc.add_paragraph(); r = h.add_run("Chapitre 3 : La photosynthèse"); r.bold = True; r.font.size = Pt(20); r.font.color.rgb = TITLE
h.alignment = WD_ALIGN_PARAGRAPH.CENTER
p = doc.add_paragraph(); r = p.add_run("Classe de Seconde — Sciences de la Vie et de la Terre"); r.italic = True; r.font.size = Pt(10)
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
doc.add_heading("I. Définition", level=1)
p = doc.add_paragraph("La ")
p.add_run("photosynthèse").bold = True
p.add_run(" est le processus par lequel les végétaux chlorophylliens fabriquent de la matière organique à partir de dioxyde de carbone et d'eau, en utilisant l'énergie lumineuse. Elle se déroule dans les ")
r = p.add_run("chloroplastes"); r.bold = True; r.font.color.rgb = RED
p.add_run(", qui contiennent la chlorophylle.")
p = doc.add_paragraph("Équation bilan : 6 CO₂ + 6 H₂O → C₆H₁₂O₆ + 6 O₂"); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.runs[0].font.size = Pt(13); p.runs[0].bold = True
doc.add_heading("II. Les conditions nécessaires", level=1)
for t in ["La lumière, qui fournit l'énergie nécessaire à la réaction.", "Le dioxyde de carbone, absorbé par les stomates des feuilles.",
          "L'eau, prélevée dans le sol par les racines.", "La chlorophylle, pigment vert qui capte la lumière."]:
    doc.add_paragraph(t, style="List Bullet")
doc.add_picture(os.path.join(out, "_diagram.png"), width=Cm(12))
c = doc.add_paragraph("Figure 1 : Schéma simplifié de la photosynthèse dans une cellule végétale."); c.runs[0].italic = True; c.runs[0].font.size = Pt(9)
c.alignment = WD_ALIGN_PARAGRAPH.CENTER
doc.add_heading("III. Résultats d'une expérience", level=1)
doc.add_paragraph("On mesure le volume de dioxygène dégagé par une plante aquatique (élodée) selon l'intensité lumineuse :")
t = doc.add_table(rows=1, cols=3); t.style = "Table Grid"
for i, x in enumerate(["Intensité lumineuse (lux)", "Volume de O₂ (mL/h)", "Observation"]):
    cell = t.rows[0].cells[i]; cell.text = x; cell.paragraphs[0].runs[0].bold = True
for row in [("0", "0", "Aucune bulle"), ("2 000", "1,5", "Quelques bulles"), ("5 000", "4,2", "Dégagement régulier"), ("10 000", "6,8", "Dégagement important")]:
    cells = t.add_row().cells
    for i, x in enumerate(row): cells[i].text = x
doc.add_heading("Exercices", level=1)
for i, q in enumerate(["Expliquez pourquoi une plante placée dans l'obscurité ne produit pas de dioxygène.",
                       "Calculez l'augmentation du volume de dioxygène entre 2 000 et 10 000 lux.",
                       "Proposez une hypothèse pour expliquer la couleur verte des feuilles."], 1):
    p = doc.add_paragraph(); r = p.add_run(f"Exercice {i}. "); r.bold = True; p.add_run(q)
p = doc.add_paragraph(); r = p.add_run("À retenir : sans lumière, pas de photosynthèse !"); r.bold = True; r.font.color.rgb = RED
p.paragraph_format.left_indent = Cm(1)
doc.save(os.path.join(out, "cours_svt_photosynthese.docx"))
subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir", out, os.path.join(out, "cours_svt_photosynthese.docx")], check=True, capture_output=True)
os.rename(os.path.join(out, "cours_svt_photosynthese.pdf"), os.path.join(out, "cours_svt_photosynthese_export.pdf"))

# Slides.
prs = Presentation()
s = prs.slides.add_slide(prs.slide_layouts[0]); s.shapes.title.text = "La Révolution française"; s.placeholders[1].text = "Histoire — Classe de Quatrième"
s = prs.slides.add_slide(prs.slide_layouts[1]); s.shapes.title.text = "Les causes de la Révolution"
tf = s.placeholders[1].text_frame; tf.text = "Une crise financière : le royaume est endetté."
for t in ["Une société inégalitaire divisée en trois ordres.", "La diffusion des idées des philosophes des Lumières.", "De mauvaises récoltes et la hausse du prix du pain."]:
    tf.add_paragraph().text = t
s = prs.slides.add_slide(prs.slide_layouts[5]); s.shapes.title.text = "Les grandes dates"
tb = s.shapes.add_textbox(Inches(1), Inches(2), Inches(8), Inches(3)).text_frame
tb.text = "5 mai 1789 : ouverture des États généraux."; tb.paragraphs[0].runs[0].font.bold = True
for t in ["14 juillet 1789 : prise de la Bastille.", "26 août 1789 : Déclaration des droits de l'homme et du citoyen."]:
    para = tb.add_paragraph(); para.text = t; para.runs[0].font.size = PPt(20)
s.shapes.add_picture(os.path.join(out, "_diagram.png"), Inches(6), Inches(5), width=Inches(3))
prs.save(os.path.join(out, "histoire_revolution.pptx"))

# Worksheet.
wb = Workbook(); ws = wb.active; ws.title = "Notes"
ws.append(["Élève", "Mathématiques", "Français", "Moyenne", "Appréciation"])
for c in ws[1]: c.font = Font(bold=True, color="FFFFFF"); c.fill = PatternFill("solid", fgColor="1F4E99"); c.alignment = Alignment(horizontal="center")
for row in [("Amina", 15, 13, "=AVERAGE(B2:C2)", "Très bon travail, continuez ainsi."), ("Paul", 9, 11, "=AVERAGE(B3:C3)", "Des efforts à fournir en calcul."),
            ("Fatou", 17, 16, "=AVERAGE(B4:C4)", "Excellent trimestre, félicitations.")]:
    ws.append(row)
ws.column_dimensions["E"].width = 40
wb.save(os.path.join(out, "bulletin_notes.xlsx"))
os.remove(os.path.join(out, "_diagram.png"))
print(sorted(os.listdir(out)))
