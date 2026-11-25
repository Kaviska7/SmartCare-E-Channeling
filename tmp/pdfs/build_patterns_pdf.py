from pathlib import Path
from html import escape
import re
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Preformatted, KeepTogether
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'output/pdf/SmartCare-Design-Patterns-With-Code.pdf'
OUT.parent.mkdir(parents=True, exist_ok=True)
TEAL = colors.HexColor('#087D78')
DARK = colors.HexColor('#123E45')
MUTED = colors.HexColor('#52666C')
PALE = colors.HexColor('#EAF5F3')
LINE = colors.HexColor('#D5E6E2')
for name,file in [('Study','arial.ttf'),('StudyBold','arialbd.ttf'),('StudyMono','consola.ttf')]:
    pdfmetrics.registerFont(TTFont(name,str(Path('C:/Windows/Fonts')/file)))
pdfmetrics.registerFontFamily('Study',normal='Study',bold='StudyBold',italic='Study',boldItalic='StudyBold')
W,H = A4
M = 44
CW = W - 2*M
body = ParagraphStyle('body',fontName='Study',fontSize=10,leading=14.5,textColor=DARK,spaceAfter=8,allowWidows=0,allowOrphans=0)
heading = ParagraphStyle('heading',parent=body,fontName='StudyBold',fontSize=19,leading=24,textColor=TEAL,spaceAfter=12,keepWithNext=True)
sub = ParagraphStyle('sub',parent=body,fontName='StudyBold',fontSize=12,leading=16,textColor=TEAL,spaceBefore=10,spaceAfter=7,keepWithNext=True)
caption = ParagraphStyle('caption',parent=body,fontName='StudyMono',fontSize=8,leading=11,textColor=MUTED,spaceAfter=5,keepWithNext=True,splitLongWords=True)
code_style = ParagraphStyle('code',fontName='StudyMono',fontSize=8.2,leading=10.4,textColor=DARK)
cell = ParagraphStyle('cell',parent=body,fontSize=9,leading=12,spaceAfter=0)
head_cell = ParagraphStyle('headCell',parent=cell,fontName='StudyBold',textColor=colors.white)

def p(text,style=body):
    return Paragraph(escape(text).replace('\n','<br/>'),style)

def block(code):
    code=code.strip('\n')
    for line in code.splitlines():
        assert pdfmetrics.stringWidth(line,'StudyMono',8.2) <= CW-22, 'Code line too wide: '+line
    panel=Table([[Preformatted(code,code_style)]],colWidths=[CW])
    panel.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),('BOX',(0,0),(-1,-1),.5,LINE),
                              ('LEFTPADDING',(0,0),(-1,-1),11),('RIGHTPADDING',(0,0),(-1,-1),11),
                              ('TOPPADDING',(0,0),(-1,-1),10),('BOTTOMPADDING',(0,0),(-1,-1),10)]))
    return panel

modules = [
('Patient management','DAO / Repository','Service Layer',[
('Pattern A: DAO / Repository','PatientRepository.java - emailCount()',r'''
public int emailCount(String email) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM app_user WHERE email=?",
        Integer.class,
        email
    );
}
''','The repository handles persistence. The service asks whether an email exists without knowing the SQL implementation.'),
('Pattern B: Service Layer','PatientService.java - registerByAdmin()',r'''
@Transactional
public long registerByAdmin(
        Authentication auth,
        PatientController.Registration r) {

    requireAdmin(auth);
    long id = register(r, false);
    users.audit(auth, "CREATE_PATIENT", "app_user", id);
    return id;
}
''','The service centralizes the business operation. Registration, authorization, and auditing are coordinated within one transaction.')]),
('Doctor management - your part','DAO / Repository','Observer-style events',[
('Pattern A: DAO / Repository','DoctorRepository.java - addSchedule()',r'''
public long addSchedule(
        long d, long b, LocalDate date,
        LocalTime s, LocalTime e, int m, int c) {

    return db.queryForObject(
        """
        INSERT INTO schedule_template(
            doctor_id, branch_id, day_of_week, schedule_date,
            start_time, end_time, slot_minutes, capacity
        )
        OUTPUT INSERTED.id
        VALUES(?,?,?,?,CAST(? AS time),CAST(? AS time),?,?)
        """,
        Long.class,
        d, b, date.getDayOfWeek().getValue(), date,
        s.toString(), e.toString(), m, c
    );
}
''','DoctorService validates ownership, dates, branches, and overlaps. DoctorRepository then performs the database insert and returns the generated schedule ID.'),
('Pattern B: Observer-style events','DoctorService.java - inside addSchedule()',r'''
events.publishEvent(
    new ScheduleEvent(
        id,
        "A consultation schedule was added for "
            + p.scheduleDate()
            + " from " + p.startTime()
            + " to " + p.endTime() + "."
    )
);
''','My doctor service publishes a schedule-change event. The notification service listens after commit and creates the alert. This separates scheduling from notification storage.')]),
('Appointment management','DAO / Repository','Observer-style events',[
('Pattern A: DAO / Repository','AppointmentRepository.java - changeSlot()',r'''
public void changeSlot(long appointmentId, long slotId) {
    db.update(
        """
        UPDATE appointment
        SET slot_id=?, updated_at=SYSUTCDATETIME()
        WHERE id=?
        """,
        slotId,
        appointmentId
    );
}
''','Appointment persistence is isolated in the repository. The service checks whether the appointment and replacement slot can be changed.'),
('Pattern B: Observer-style events','AppointmentService.java - inside book()',r'''
users.audit(auth, "BOOK_APPOINTMENT", "appointment", id);

events.publishEvent(
    new AppointmentEvent(id, "BOOKED")
);

return id;
''','Booking publishes an event. The observer creates alerts after the booking commits, without putting notification logic inside appointment booking.')]),
('Queue management','DAO / Repository','Observer-style events',[
('Pattern A: DAO / Repository','QueueRepository.java - transition()',r'''
public int transition(long id, String from, String to) {
    return db.update(
        """
        UPDATE queue_token
        SET status=?,
            called_at=CASE
                WHEN ?='CALLED' THEN SYSUTCDATETIME()
                ELSE called_at
            END,
            completed_at=CASE
                WHEN ?<>'CALLED' THEN SYSUTCDATETIME()
                ELSE completed_at
            END
        WHERE id=? AND status=?
        """,
        to, to, to, id, from
    );
}
''','The repository changes a token only when its current status matches the expected status. The update count tells the service whether the transition succeeded.'),
('Pattern B: Observer-style events','QueueService.java - inside callNext()',r'''
long appointment = number(row, "appointment_id");

repo.appointmentStatus(appointment, "CALLED");
users.audit(auth, "CALL_TOKEN", "queue_token", token);

events.publishEvent(
    new AppointmentEvent(appointment, "CALLED")
);

return token;
''','Calling a token publishes an event. The notification listener uses it to notify the patient and assigned doctor.')]),
('Notifications and feedback','Observer-style events','DAO / Repository',[
('Pattern A: Observer-style events','NotificationService.java - onAppointment()',r'''
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onAppointment(AppointmentEvent event) {
    try {
        saveAppointmentAlerts(event);
    } catch (RuntimeException e) {
        log.error(
            "Appointment {} committed, but its in-app alerts "
                + "could not be saved: {}",
            event.appointmentId(), e.toString()
        );
    }
}
''','The notification module observes events from appointments, queues, and doctor schedules. It reacts after successful transactions.'),
('Pattern B: DAO / Repository','NotificationRepository.java - add()',r'''
public void add(
        long userId,
        Long appointmentId,
        String type,
        String message,
        LocalDateTime dueAt) {

    db.update(
        """
        INSERT INTO notification(
            user_id, appointment_id, type, message, due_at
        )
        VALUES(?,?,?,?,?)
        """,
        userId, appointmentId, type, message, dueAt
    );
}
''','The service decides which message to create; the repository stores it. Feedback follows the same separation through ReviewService and ReviewRepository.')]),
('Report management','Strategy','DAO / Repository',[
('Pattern A: Strategy','ReportExporter.java - common contract',r'''
public interface ReportExporter {
    String format();
    String contentType();

    byte[] export(Map<String, Object> report) throws IOException;
}
''','The export algorithm changes according to the requested format. PDF, Excel, and CSV exporters share this interface, so report generation does not contain every format implementation.'),
('Pattern B: DAO / Repository','ReportManagementRepository.java - setScheduleActive()',r'''
public int setScheduleActive(
        long ownerId, long id, boolean active) {

    return db.update(
        """
        UPDATE report_schedule
        SET active=?, updated_at=SYSUTCDATETIME()
        WHERE owner_user_id=? AND id=?
        """,
        active, ownerId, id
    );
}
''','Report storage is separated from report business logic. This query also scopes the update to the schedule owner.')])
]

schedule_record = 'public record ScheduleEvent(long doctorId, String message) {}'
schedule_listener = r'''
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onSchedule(ScheduleEvent event) {
    try {
        Long doctor = repo.doctorUser(event.doctorId());

        if (doctor != null) {
            repo.add(
                doctor, null, "SCHEDULE_CHANGED",
                event.message(), null
            );
        }
    } catch (RuntimeException e) {
        log.error(
            "Doctor {} schedule changed, but its in-app alert "
                + "could not be saved: {}",
            event.doctorId(), e.toString()
        );
    }
}
'''
appointment_listener = modules[4][3][0][2]
export_selection = r'''
public ReportExporter exporter(String format) {
    ReportExporter exporter = exporters.get(format);

    if (exporter == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Choose pdf, xlsx, or csv"
        );
    }

    return exporter;
}

public byte[] export(
        ReportExporter exporter,
        Map<String, Object> data) throws IOException {

    return exporter.export(data);
}
'''

class Numbered(canvas.Canvas):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        self.states=[]
    def showPage(self):
        self.states.append(dict(self.__dict__))
        self._startPage()
    def save(self):
        total=len(self.states)
        for state in self.states:
            self.__dict__.update(state)
            self.setStrokeColor(LINE)
            self.line(M,37,W-M,37)
            self.setFillColor(MUTED)
            self.setFont('Study',8)
            self.drawString(M,24,'SMARTCARE / DESIGN PATTERNS WITH CODE')
            self.drawRightString(W-M,24,f'{self._pageNumber} / {total}')
            super().showPage()
        super().save()

def background(c,doc):
    if doc.page==1:
        c.setFillColor(TEAL)
        c.rect(0,H-235,W,235,fill=1,stroke=0)
        c.setFillColor(colors.HexColor('#116E6D'))
        c.circle(W,H,130,fill=1,stroke=0)
        c.setFillColor(colors.white)
        c.setFont('StudyBold',12)
        c.drawString(M,H-54,'SMARTCARE E-CHANNELING')
        c.setFont('StudyBold',32)
        c.drawString(M,H-112,'Design Patterns')
        c.setFont('Study',23)
        c.drawString(M,H-148,'With Code')
        c.setFont('Study',10)
        c.drawString(M,H-188,'Six modules. Twelve examples. Ready for your viva.')
    else:
        c.setFillColor(TEAL)
        c.rect(M,H-31,26,3,fill=1,stroke=0)
        c.setFillColor(MUTED)
        c.setFont('Study',8)
        c.drawString(M+34,H-33,'SMARTCARE / CODE EXPLANATION GUIDE')

story=[Spacer(1,211),p('Read the code. Explain the reason.',heading),
       p('A study guide to two illustrated patterns in each SmartCare module, with existing-code excerpts and short viva explanations. Prepared on October 5, 2026.'),
       p('Important distinction',sub),
       p('DAO/Repository and Service Layer are enterprise or architectural patterns. Observer and Strategy are Gang of Four (GoF) patterns. These examples do not establish two GoF patterns in every module.'),
       p('How to use this guide',sub),
       p('First identify the class, then explain its responsibility, then describe why the separation is useful. For your doctor module, focus on schedule persistence and ScheduleEvent publication/listening.'),
       p('The code is reformatted from existing source. Imports, class declarations, injected fields, and some surrounding method logic are omitted. These are excerpts to study, not standalone replacement files.'),
       PageBreak(),p('Patterns at a glance',heading)]
rows=[[p('Module',head_cell),p('Pattern A',head_cell),p('Pattern B',head_cell)]]
for name,a,b,_ in modules:
    rows.append([p(name,cell),p(a,cell),p(b,cell)])
table=Table(rows,colWidths=[CW*.39,CW*.30,CW*.31])
table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),TEAL),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,PALE]),
                         ('VALIGN',(0,0),(-1,-1),'TOP'),('LINEBELOW',(0,0),(-1,-1),.4,LINE),
                         ('LEFTPADDING',(0,0),(-1,-1),8),('RIGHTPADDING',(0,0),(-1,-1),8),
                         ('TOPPADDING',(0,0),(-1,-1),10),('BOTTOMPADDING',(0,0),(-1,-1),10)]))
story.extend([table,Spacer(1,15),p('What each pattern means',sub),
              p('DAO / Repository: keep database operations behind a dedicated persistence component.'),
              p('Service Layer: coordinate business rules, permissions, and related operations in service methods.'),
              p('Observer-style events: a publisher announces a change and listeners react without embedding their responsibilities in the publisher.'),
              p('Strategy: select interchangeable implementations of a common operation, such as exporting PDF, Excel, or CSV.'),
              p('Do not overclaim',sub),
              p('Status values and transition checks alone do not demonstrate the GoF State pattern. Spring-managed beans alone do not demonstrate a custom Singleton pattern. If your rubric specifically demands two GoF patterns per module, additional implementations would be needed.')])

for n,(name,a,b,patterns) in enumerate(modules,1):
    story.extend([PageBreak(),p(f'{n}. {name}',heading)])
    for idx,(label,source,code,explanation) in enumerate(patterns):
        story.extend([p(label,sub),p(source,caption),block(code),Spacer(1,7)])
        if n==2 and idx==1:
            story.extend([p('ScheduleEvent.java - event data',caption),block(schedule_record),Spacer(1,7),
                          p('NotificationService.java - observer',caption),block(schedule_listener),Spacer(1,7)])
        if n==3 and idx==1:
            story.extend([p('NotificationService.java - observer',caption),block(appointment_listener),Spacer(1,7)])
        if n==6 and idx==0:
            story.extend([p('ReportService.java - selecting and executing the strategy',caption),block(export_selection),Spacer(1,7)])
        story.append(p('Viva explanation: '+explanation))
    if n==4:
        story.append(p('Remember: this transition code enforces workflow rules. It does not implement separate State objects.'))

story.extend([Spacer(1,14),p('Find the source files',sub),
              p('Backend modules: src/main/java/lk/sunshine/smartcare/features/'),
              p('Patient: patient/PatientRepository.java and PatientService.java\n'
                'Doctor: doctor/DoctorRepository.java and DoctorService.java\n'
                'Appointment: appointment/AppointmentRepository.java and AppointmentService.java\n'
                'Queue: queue/QueueRepository.java and QueueService.java\n'
                'Notifications: notification/NotificationService.java, NotificationRepository.java, ScheduleEvent.java\n'
                'Reports: report/ReportExporter.java, ReportService.java, ReportManagementRepository.java',caption)])

doc=SimpleDocTemplate(str(OUT),pagesize=A4,leftMargin=M,rightMargin=M,topMargin=52,bottomMargin=54,
                      title='SmartCare - Design Patterns With Code',author='SmartCare E-Channeling',
                      subject='Two illustrated design patterns per module, code excerpts, and viva explanations')
doc.build(story,onFirstPage=background,onLaterPages=background,canvasmaker=Numbered)
reader=PdfReader(str(OUT))
text='\n'.join(page.extract_text() or '' for page in reader.pages)
for name,_,_,_ in modules:
    assert name in text, 'Missing module: '+name
assert text.count('Pattern A:')==6 and text.count('Pattern B:')==6
assert text.count('Viva explanation:')==12
for name in ['emailCount','registerByAdmin','addSchedule','onSchedule','changeSlot','onAppointment','transition','setScheduleActive','ReportExporter']:
    assert name in text, 'Missing code example: '+name
assert '\ufffd' not in text
print(f'Created {OUT.name}: {len(reader.pages)} pages; six modules, twelve patterns and explanations verified.')
