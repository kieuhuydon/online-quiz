package quiz.client.ui.student;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import quiz.client.api.QuizApi;
import quiz.client.ui.*;
import quiz.client.util.FxAsync;
import quiz.common.dto.ResultDto;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class HistoryController extends ScreenController {
    private final QuizApi api;private final SceneNavigator navigator;private final FxAsync fx;
    @FXML private TableView<ResultDto> table;@FXML private Label notice;@FXML private ProgressIndicator progress;
    public HistoryController(QuizApi api,SceneNavigator navigator,FxAsync fx){this.api=api;this.navigator=navigator;this.fx=fx;}
    @FXML private void initialize(){col("Bài kiểm tra",r->r.quizTitle(),190);col("Điểm",r->String.format(Locale.ROOT,"%.2f / 10",r.score()),90);col("Đúng / Tổng",r->r.correctCount()+" / "+r.totalQuestions(),100);col("Kết quả",r->r.passed()?"Đạt":"Chưa đạt",100);col("Nộp lúc",r->DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(r.submittedAt()),150);col("Hình thức nộp",r->switch(r.submitReason()){case MANUAL->"Nộp thủ công";case TIMEOUT->"Tự động nộp — Hết giờ";case ANTICHEAT->"Tự động nộp — Vi phạm";},190);table.setPlaceholder(new Label("Chưa có kết quả bài thi nào."));table.setFixedCellSize(42);}
    private void col(String name,java.util.function.Function<ResultDto,String> value,int width){TableColumn<ResultDto,String> c=new TableColumn<>(name);c.setPrefWidth(width);c.setCellValueFactory(x->new SimpleStringProperty(value.apply(x.getValue())));table.getColumns().add(c);}
    @Override protected void onMount(){reload();}
    @FXML private void reload(){progress.setVisible(true);fx.observe(api.listMyHistory(),this::isActive,items->{table.setItems(FXCollections.observableArrayList(items));notice.setText("");notice.setVisible(false);notice.setManaged(false);progress.setVisible(false);},e->{notice.setText("Không tải được lịch sử: "+e.getMessage());notice.setVisible(true);notice.setManaged(true);progress.setVisible(false);},()->progress.setVisible(false));}
    @FXML private void exams(){navigator.show(Screen.STUDENT_DASHBOARD);}
}
