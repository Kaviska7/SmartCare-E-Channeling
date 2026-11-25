from pathlib import Path
import re
import html
from reportlab.pdfgen import canvas
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, PageBreak, LongTable,
    TableStyle, Flowable, KeepTogether,
)
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'output/pdf/SmartCare-Doctor-Viva-Notes.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)
SOURCE = ROOT / 'docs/doctor-viva-notes.md'
TEAL = colors.HexColor('#087D78')
DARK = colors.HexColor('#123E45')
MUTED = colors.HexColor('#52666C')
PALE = colors.HexColor('#EAF5F3')
LINE = colors.HexColor('#D5E6E2')

for name, file in [('Viva','arial.ttf'), ('VivaBold','arialbd.ttf'),
                   ('VivaItalic','ariali.ttf'), ('VivaMono','consola.ttf')]:
    pdfmetrics.registerFont(TTFont(name, str(Path('C:/Windows/Fonts') / file)))
pdfmetrics.registerFontFamily('Viva', normal='Viva', bold='VivaBold', italic='VivaItalic', boldItalic='VivaBold')

styles = getSampleStyleSheet()
styles.add(ParagraphStyle('VivaBody', fontName='Viva', fontSize=10, leading=15,
                          textColor=DARK, spaceAfter=7, splitLongWords=True,
                          allowWidows=0, allowOrphans=0))
styles.add(ParagraphStyle('VivaSection', fontName='VivaBold', fontSize=17, leading=22,
                          textColor=TEAL, spaceBefore=17, spaceAfter=9, keepWithNext=True))
styles.add(ParagraphStyle('VivaSubsection', fontName='VivaBold', fontSize=11.5, leading=16,
                          textColor=DARK, spaceBefore=10, spaceAfter=5, keepWithNext=True))
styles.add(ParagraphStyle('VivaCell', parent=styles['VivaBody'], fontSize=8.8, leading=12.5,
                          spaceAfter=0))
styles.add(ParagraphStyle('VivaHeaderCell', parent=styles['VivaCell'], fontName='VivaBold',
                          textColor=colors.white))
styles.add(ParagraphStyle('VivaCode', fontName='VivaMono', fontSize=8.6, leading=12,
                          backColor=PALE, borderPadding=9, spaceBefore=5, spaceAfter=10,
                          textColor=DARK, splitLongWords=True))
styles.add(ParagraphStyle('VivaQuestion', parent=styles['VivaBody'], fontName='VivaBold',
                          textColor=TEAL, spaceBefore=8, spaceAfter=4, keepWithNext=True))

def normalized(text):
    return (text.replace('—','-').replace('–','-').replace('‑','-')
            .replace('“','"').replace('”','"').replace('’', "'")
            .replace('‘', "'").replace('→', ' > ').replace('☐', '[ ]'))

def inline(text):
    text = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', normalized(text))
    text = html.escape(text)
    text = re.sub(r'`([^`]+)`', r'<font name="VivaMono" size="9">\1</font>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<b>\1</b>', text)
    return text

def paragraph(text, style='VivaBody'):
    return Paragraph(inline(text), styles[style])

WIDTH, HEIGHT = A4
MARGIN = 45
CONTENT = WIDTH - MARGIN * 2

class Architecture(Flowable):
    def __init__(self):
        Flowable.__init__(self)
        self.width = CONTENT
        self.height = 123

    def draw(self):
        c = self.canv
        box_w = 88
        gap = (CONTENT - 5 * box_w) / 4
        labels = [('React UI','Doctor.jsx'), ('Controller','HTTP + validation'),
                  ('Service','Rules + permissions'), ('Repository','JdbcTemplate'), ('SQL Server','Persistent data')]
        for i, (name, detail) in enumerate(labels):
            x = i * (box_w + gap)
            c.setFillColor(PALE)
            c.setStrokeColor(LINE)
            c.roundRect(x, 64, box_w, 46, 7, fill=1, stroke=1)
            c.setFillColor(DARK)
            c.setFont('VivaBold', 9)
            c.drawCentredString(x + box_w/2, 91, name)
            c.setFont('Viva', 7)
            c.drawCentredString(x + box_w/2, 77, detail)
            if i < 4:
                c.setStrokeColor(TEAL)
                c.line(x + box_w + 2, 87, x + box_w + gap - 2, 87)
                c.line(x + box_w + gap - 5, 90, x + box_w + gap - 2, 87)
                c.line(x + box_w + gap - 5, 84, x + box_w + gap - 2, 87)
        c.setFont('Viva', 9)
        c.setFillColor(MUTED)
        c.drawCentredString(CONTENT/2, 39, 'The service also uses CurrentUser and PasswordEncoder.')
        c.drawCentredString(CONTENT/2, 22, 'ScheduleEvent > NotificationService > in-app alert after commit')

class NumberedCanvas(canvas.Canvas):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.saved = []

    def showPage(self):
        self.saved.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        total = len(self.saved)
        for state in self.saved:
            self.__dict__.update(state)
            self.footer(total)
            super().showPage()
        super().save()

    def footer(self, total):
        n = self._pageNumber
        self.setStrokeColor(LINE)
        self.line(MARGIN, 39, WIDTH-MARGIN, 39)
        self.setFillColor(MUTED)
        self.setFont('Viva', 8)
        self.drawString(MARGIN, 25, 'SMARTCARE  |  DOCTOR MANAGEMENT  |  VIVA GUIDE')
        self.drawRightString(WIDTH-MARGIN, 25, f'{n} / {total}')

def page_background(c, doc):
    if doc.page == 1:
        c.setFillColor(TEAL)
        c.rect(0, HEIGHT-245, WIDTH, 245, fill=1, stroke=0)
        c.setFillColor(colors.HexColor('#116E6D'))
        c.circle(WIDTH-5, HEIGHT-10, 120, fill=1, stroke=0)
        c.setFillColor(colors.white)
        c.setFont('VivaBold', 12)
        c.drawString(MARGIN, HEIGHT-55, 'SMARTCARE E-CHANNELING')
        c.setFont('VivaBold', 32)
        c.drawString(MARGIN, HEIGHT-113, 'Doctor Management')
        c.setFont('Viva', 22)
        c.drawString(MARGIN, HEIGHT-148, 'Final Viva Notes')
        c.setFont('Viva', 10)
        c.drawString(MARGIN, HEIGHT-185, 'Code explained. Workflows understood. Answers prepared.')
    else:
        c.setFillColor(TEAL)
        c.rect(MARGIN, HEIGHT-32, 28, 3, fill=1, stroke=0)
        c.setFillColor(MUTED)
        c.setFont('Viva', 8)
        c.drawString(MARGIN+37, HEIGHT-33, 'SMARTCARE / DOCTOR VIVA NOTES')

story = [Spacer(1, 224)]
story.append(paragraph('Your complete study guide', 'VivaSection'))
story.append(paragraph('Prepared from the current project code on October 5, 2026. Includes doctor CRUD, validations, database relationships, security, a demonstration plan, and 32 practice questions.'))
story.append(Spacer(1, 8))
cover_rows = [
    ['Start here', 'Sections 1-2: introduction and facts to remember'],
    ['Explain your code', 'Sections 4-11: architecture, data, CRUD, slots, leave, and security'],
    ['Prepare your demo', 'Section 17: a practical five-minute sequence'],
    ['Practise answers', 'Section 18: 32 viva questions and short answers'],
    ['Revise before the viva', 'Sections 19-20: limitations and tonight\'s checklist'],
]
cover_table = LongTable([[paragraph(x, 'VivaCell') for x in row] for row in cover_rows],
                        colWidths=[128, CONTENT-128])
cover_table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),
                               ('BOX',(0,0),(-1,-1),.6,LINE),
                               ('LINEBELOW',(0,0),(-1,-2),.4,LINE),
                               ('VALIGN',(0,0),(-1,-1),'TOP'),
                               ('LEFTPADDING',(0,0),(-1,-1),11),
                               ('RIGHTPADDING',(0,0),(-1,-1),11),
                               ('TOPPADDING',(0,0),(-1,-1),10),
                               ('BOTTOMPADDING',(0,0),(-1,-1),10)]))
story.append(cover_table)
story.append(Spacer(1, 16))
story.append(paragraph('Key facts: JDBC for doctors. Session authentication. Specific consultation dates. Manual call-next by reception.'))
story.append(paragraph('Scope: explain your doctor-management contribution and its integration with the other members\' appointment, queue, notification, and authentication features.'))
story.append(PageBreak())

lines = SOURCE.read_text(encoding='utf-8').splitlines()
i = 0
in_questions = False
section_count = 0
while i < len(lines):
    line = lines[i].strip()
    if not line or line.startswith('# ') or line.startswith('**SmartCare E-Channeling'):
        i += 1
        continue
    if line.startswith('## '):
        section_count += 1
        in_questions = line.startswith('## 18.')
        if line.startswith(('## 13.', '## 17.', '## 18.')):
            story.append(PageBreak())
        story.append(paragraph(line[3:], 'VivaSection'))
        i += 1
        continue
    if line.startswith('### '):
        story.append(paragraph(line[4:], 'VivaSubsection'))
        i += 1
        continue
    if line.startswith('```'):
        language = line[3:]
        block = []
        i += 1
        while i < len(lines) and not lines[i].strip().startswith('```'):
            block.append(lines[i])
            i += 1
        if language == 'mermaid':
            story.append(Architecture())
        else:
            content = '<br/>'.join(html.escape(normalized(x)).replace(' ', '&nbsp;') for x in block)
            story.append(Paragraph(content, styles['VivaCode']))
        i += 1
        continue
    if line.startswith('|'):
        rows = []
        while i < len(lines) and lines[i].strip().startswith('|'):
            row = [cell.strip() for cell in lines[i].strip().strip('|').split('|')]
            if not all(re.fullmatch(r':?-+:?', x) for x in row):
                rows.append(row)
            i += 1
        columns = len(rows[0])
        if columns == 2:
            first = .33 if 'File' not in rows[0][0] else .30
            widths = [CONTENT*first, CONTENT*(1-first)]
        elif columns == 3:
            widths = [CONTENT*.40, CONTENT*.38, CONTENT*.22]
            if 'Method' in rows[0][0]:
                widths = [CONTENT*.53, CONTENT*.29, CONTENT*.18]
        else:
            widths = [CONTENT*.37] + [CONTENT*.63/(columns-1)]*(columns-1)
        cells = [[paragraph(cell, 'VivaHeaderCell' if r==0 else 'VivaCell')
                  for cell in row] for r, row in enumerate(rows)]
        table = LongTable(cells, colWidths=widths, repeatRows=1, hAlign='LEFT')
        table.setStyle(TableStyle([
            ('BACKGROUND',(0,0),(-1,0),TEAL),
            ('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,PALE]),
            ('VALIGN',(0,0),(-1,-1),'TOP'),
            ('LINEBELOW',(0,0),(-1,-1),.4,LINE),
            ('LEFTPADDING',(0,0),(-1,-1),8),
            ('RIGHTPADDING',(0,0),(-1,-1),8),
            ('TOPPADDING',(0,0),(-1,-1),7),
            ('BOTTOMPADDING',(0,0),(-1,-1),7),
        ]))
        story.extend([table, Spacer(1, 10)])
        continue
    if line.startswith('- '):
        item = line[2:]
        is_check = item.startswith('[ ] ')
        item = item[4:] if is_check else item
        p = paragraph(('[ ] ' if is_check else '- ') + item)
        story.append(p)
        i += 1
        continue
    if re.match(r'^\d+\. ', line):
        story.append(paragraph(line))
        i += 1
        continue
    if in_questions and re.match(r'^\*\*\d+\.', line):
        story.append(paragraph(line, 'VivaQuestion'))
        i += 1
        continue
    text = [line]
    i += 1
    while i < len(lines) and lines[i].strip() and not re.match(r'^(#|\||```|- |\d+\. )', lines[i].strip()):
        text.append(lines[i].strip())
        i += 1
    value = ' '.join(text)
    style = 'VivaQuestion' if in_questions and re.match(r'^\*\*\d+\.', value) else 'VivaBody'
    story.append(paragraph(value, style))

doc = SimpleDocTemplate(str(OUT), pagesize=A4, rightMargin=MARGIN, leftMargin=MARGIN,
                        topMargin=54, bottomMargin=55,
                        title='SmartCare - Doctor Management Final Viva Notes',
                        author='SmartCare E-Channeling',
                        subject='Doctor CRUD, validations, architecture, workflows, and viva questions')
doc.build(story, onFirstPage=page_background, onLaterPages=page_background,
          canvasmaker=NumberedCanvas)
reader = PdfReader(str(OUT))
text = '\n'.join(p.extract_text() or '' for p in reader.pages)
for section in range(1,21):
    assert re.search(rf'\b{section}\. ', text), f'Missing section {section}'
for expected in ['32. What improvements', 'POST /api/doctors/with-account',
                 'Validations', 'CRUD', 'Medical registration', 'tonight']:
    assert expected.lower() in text.lower(), f'Missing content: {expected}'
assert '\ufffd' not in text, 'Replacement characters in extracted text'
compact_pdf = re.sub(r'\W', '', text).casefold()
questions = [line for line in lines if re.match(r'^\*\*\d+\.', line.strip())]
assert len(questions) == 32
for question in questions:
    compact_question = re.sub(r'\W', '', normalized(question)).casefold()
    assert compact_question in compact_pdf, f'Missing question: {question}'
print(f'Created {OUT.name}: {len(reader.pages)} pages; all 20 sections and 32 questions present.')
