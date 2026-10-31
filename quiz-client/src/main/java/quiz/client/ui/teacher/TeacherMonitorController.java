package quiz.client.ui.teacher;

import javafx.animation.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;
import quiz.client.api.QuizApi;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.enums.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

/** Programmatic JavaFX view; all network callbacks use FxAsync (Platform.runLater). */
public final class TeacherMonitorController implements AutoCloseable {
    private final QuizApi api;
    private final FxAsync fx;
    private final long examId;
    private final BooleanSupplier parentActive;
    private final Dialog<Void> dialog=new Dialog<>();
    private final TableView<ExamSession> table=new TableView<>();
    private final Label notice=new Label("Đang tải...");
    private final Button score=new Button("Trừ điểm"),time=new Button("Trừ thời gian"),stop=new Button("Kết thúc bài thi");
    private final Timeline refresh;
    private boolean loading,mutating,closed;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy").withZone(ZoneId.systemDefault());

    public TeacherMonitorController(QuizApi api,FxAsync fx,long examId,String title,Window owner,BooleanSupplier parentActive) {
        this.api=api;this.fx=fx;this.examId=examId;this.parentActive=parentActive;
        dialog.setTitle("Giám sát: "+title);dialog.setHeaderText("Giám sát phòng thi · cập nhật mỗi "+api.monitoringIntervalMs()/1000.0+" giây");
        if(owner!=null) dialog.initOwner(owner);
        dialog.setResizable(true);dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        table.setId("monitorTable");score.setId("monitorScore");time.setId("monitorTime");stop.setId("monitorStop");
        configureTable();
        score.setOnAction(e->penalty(PenaltyType.DEDUCT_SCORE));
        time.setOnAction(e->penalty(PenaltyType.DEDUCT_TIME));
        stop.setOnAction(e->penalty(PenaltyType.TERMINATE));
        Button history=new Button("Nhật ký / Lịch sử xử phạt");history.setOnAction(e->history());
        Button reload=new Button("Tải lại");reload.setOnAction(e->refresh());
        notice.setWrapText(true);
        Label legend=new Label("Vàng: cảnh báo · Đỏ: phát hiện ứng dụng cấm, cần xem bằng chứng. IP do máy sinh viên báo cáo.");
        legend.setWrapText(true);
        VBox content=new VBox(10,legend,table,new HBox(8,score,time,stop,history,reload),notice);
        VBox.setVgrow(table,Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        refresh=new Timeline(new KeyFrame(Duration.millis(api.monitoringIntervalMs()),e->refresh()));
        refresh.setCycleCount(Animation.INDEFINITE);
        dialog.setOnShown(e->{refresh();refresh.play();});
        dialog.setOnHidden(e->close());
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->buttons());
        buttons();
    }
    public void show() { dialog.show(); }
    private boolean active() { return !closed&&dialog.isShowing()&&parentActive.getAsBoolean(); }
    private void configureTable() {
        table.setPrefSize(1120,470);table.setPlaceholder(new Label("Chưa có sinh viên bắt đầu bài thi"));
        column("Mã SV / Họ tên",180,s->s.studentId()+" / "+s.studentName());
        column("IP",125,s->Objects.toString(s.ipAddress(),"Chưa xác định"));
        column("Trạng thái",140,s->status(s.status()));
        column("Vi phạm",70,s->Integer.toString(s.violationCount()));
        column("Chi tiết gần nhất",260,s->Objects.toString(s.violationDetails(),"—"));
        column("Điểm đã trừ",95,s->String.format("%.2f",s.deductedScore()));
        column("Giây đã trừ",95,s->Integer.toString(s.deductedTimeSeconds()));
        column("Còn lại",85,s->String.format("%02d:%02d",s.remainingSeconds()/60,s.remainingSeconds()%60));
        column("Cập nhật",140,s->DATE.format(s.lastUpdated()));
        table.setRowFactory(t->new TableRow<>() {
            @Override protected void updateItem(ExamSession s,boolean empty) {
                super.updateItem(s,empty);
                setStyle(empty||s==null?"":s.status()==MonitoringStatus.CHEATING?"-fx-background-color: #fecaca;"
                    :s.status()==MonitoringStatus.WARNING?"-fx-background-color: #fef08a;":"");
            }
        });
    }
    private void column(String name,double width,Function<ExamSession,String> value) {
        TableColumn<ExamSession,String> c=new TableColumn<>(name);c.setPrefWidth(width);
        c.setCellValueFactory(v->new SimpleStringProperty(value.apply(v.getValue())));table.getColumns().add(c);
    }
    private void refresh() {
        if(!active()||loading||mutating) return;
        loading=true;buttons();
        fx.observe(api.listExamSessions(examId),this::active,rows->{
            ExamSession selected=table.getSelectionModel().getSelectedItem();
            table.getItems().setAll(rows);
            if(selected!=null) rows.stream().filter(s->s.sessionId()==selected.sessionId()).findFirst()
                .ifPresent(s->table.getSelectionModel().select(s));
            notice.setText("Đã đồng bộ "+rows.size()+" lượt thi lúc "+java.time.LocalTime.now().withNano(0));
        },error->notice.setText("Dữ liệu chưa được cập nhật: "+error.getMessage()),()->{loading=false;buttons();});
    }
    private void buttons() {
        ExamSession s=table.getSelectionModel().getSelectedItem();
        boolean disabled=loading||mutating||s==null||s.status()==MonitoringStatus.SUBMITTED||s.status()==MonitoringStatus.TERMINATED;
        score.setDisable(disabled);time.setDisable(disabled);stop.setDisable(disabled);
    }
    private void penalty(PenaltyType type) {
        ExamSession selected=table.getSelectionModel().getSelectedItem();
        if(selected==null||mutating||loading) return;
        Dialog<ButtonType> confirm=new Dialog<>();confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
        confirm.setTitle("Xác nhận xử phạt");
        confirm.setHeaderText(selected.studentName()+" (SV "+selected.studentId()+") · "+action(type));
        TextField amount=new TextField(type==PenaltyType.DEDUCT_SCORE?"1":"300");
        TextField reason=new TextField();reason.setPromptText("Lý do xử phạt (bắt buộc, tối đa 255 ký tự)");
        VBox fields=new VBox(10,new Label(type==PenaltyType.DEDUCT_SCORE?"Số điểm trừ trên thang 10":"Số giây trừ"),amount,reason);
        if(type==PenaltyType.TERMINATE) fields.getChildren().setAll(new Label("Kết thúc và chấm các đáp án đã lưu."),reason);
        confirm.getDialogPane().setContent(fields);confirm.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        mutating=true;buttons();
        Optional<ButtonType> choice=confirm.showAndWait();
        if(choice.orElse(ButtonType.CANCEL)!=ButtonType.OK) {mutating=false;buttons();return;}
        double value;
        try {
            value=type==PenaltyType.TERMINATE?0:Double.parseDouble(amount.getText().strip());
            if(!Double.isFinite(value)||reason.getText().isBlank()||reason.getText().length()>255) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) {notice.setText("Nhập mức phạt hợp lệ và lý do tối đa 255 ký tự.");mutating=false;buttons();return;}
        ApplyPenaltyDto request=new ApplyPenaltyDto(UUID.randomUUID().toString(),selected.sessionId(),type,value,reason.getText());
        fx.observe(api.applyMonitoringPenalty(request),this::active,state->{
            notice.setText("Đã xử phạt "+selected.studentName()+". Xem lịch sử để đối chiếu.");
            int index=table.getItems().indexOf(selected);
            if(index>=0) table.getItems().set(index,state);
        },error->notice.setText("Chưa xác nhận xử phạt: "+error.getMessage()+". Tải lại và kiểm tra lịch sử trước khi thao tác tiếp."),
            ()->{mutating=false;buttons();});
    }
    private void history() {
        ExamSession selected=table.getSelectionModel().getSelectedItem();if(selected==null) return;
        Dialog<Void> view=new Dialog<>();view.initOwner(dialog.getDialogPane().getScene().getWindow());
        view.setTitle("Nhật ký: "+selected.studentName());view.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TextArea text=new TextArea("Đang tải...");text.setEditable(false);text.setWrapText(true);text.setPrefSize(750,420);
        view.getDialogPane().setContent(text);
        var data=api.listAttemptEvents(selected.sessionId()).thenCombine(api.listMonitoringPenalties(selected.sessionId()),(events,penalties)->{
            StringBuilder b=new StringBuilder("SỰ KIỆN\n");
            events.forEach(e->b.append(DATE.format(e.serverTime())).append(" · ").append(e.eventType()).append(" · ").append(e.detail()).append('\n'));
            b.append("\nXỬ PHẠT\n");
            penalties.forEach(p->b.append(DATE.format(p.appliedAt())).append(" · GV ").append(p.teacherId()).append(" · ")
                .append(action(p.type())).append(" ").append(p.amount()).append(" · ").append(p.reason()).append('\n'));
            return b.toString();
        });
        fx.observe(data,()->active()&&view.isShowing(),text::setText,e->text.setText(e.getMessage()),()->{});
        view.showAndWait();
    }
    private static String action(PenaltyType t) { return switch(t) {
        case DEDUCT_SCORE->"Trừ điểm";case DEDUCT_TIME->"Trừ thời gian (giây)";case TERMINATE->"Kết thúc bài thi";}; }
    private static String status(MonitoringStatus s) { return switch(s) {
        case IN_PROGRESS->"Đang thi";case WARNING->"Cảnh báo";case CHEATING->"Nghi vấn ứng dụng cấm";
        case SUBMITTED->"Đã nộp";case TERMINATED->"Bị kết thúc";}; }
    @Override public void close() { if(closed) return;closed=true;refresh.stop();dialog.close(); }
}
