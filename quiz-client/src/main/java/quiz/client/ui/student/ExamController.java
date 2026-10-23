package quiz.client.ui.student;

import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.util.Duration;
import quiz.client.api.QuizApi;
import quiz.client.network.PushListener;
import quiz.client.ui.*;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.protocol.MessageType;
import java.util.*;

public final class ExamController extends ScreenController {
    private final QuizApi api; private final SceneNavigator navigator; private final FxAsync fx; private final AttemptStateDto attempt;
    @FXML private Label title, timer, questionPosition, questionText, saveStatus;
    @FXML private RadioButton optionA, optionB, optionC, optionD;
    @FXML private ToggleGroup options;
    @FXML private GridPane reviewGrid;
    @FXML private Button previous, next, submit;
    private final Map<Long,Character> answers=new HashMap<>(); private Timeline countdown; private long seconds; private int index;
    public ExamController(QuizApi api,SceneNavigator navigator,FxAsync fx,AttemptStateDto attempt){this.api=api;this.navigator=navigator;this.fx=fx;this.attempt=attempt;answers.putAll(attempt.savedAnswers());seconds=attempt.remainingSeconds();}
    @FXML private void initialize(){title.setText(attempt.quizTitle());render();refreshReview();}
    @Override protected void onMount(){if(seconds<=0){submit(true);}else{countdown=new Timeline(new KeyFrame(Duration.seconds(1),e->{seconds=Math.max(0,seconds-1);updateTimer();if(seconds==0)submit(true);}));countdown.setCycleCount(Animation.INDEFINITE);countdown.play();updateTimer();}PushListener listener=(generation,response)->{if(response.pushType()==MessageType.PUSH_FORCE_SUBMITTED)fx.run(()->{if(isActive())submit(true);});};own(api.addPushListener(listener));}
    private StudentQuestionDto current(){return attempt.questions().get(index);}
    private void render(){if(attempt.questions().isEmpty()){questionText.setText("Bài kiểm tra không có câu hỏi.");return;}StudentQuestionDto q=current();questionPosition.setText("Câu "+(index+1)+" / "+attempt.questions().size());questionText.setText(q.content());optionA.setText("A. "+q.optionA());optionB.setText("B. "+q.optionB());optionC.setText("C. "+q.optionC());optionD.setText("D. "+q.optionD());Character selected=answers.get(q.id());options.selectToggle(selected==null?null:switch(selected){case 'A'->optionA;case 'B'->optionB;case 'C'->optionC;case 'D'->optionD;default->null;});previous.setDisable(index==0);next.setDisable(index>=attempt.questions().size()-1);}
    @FXML private void selectAnswer(){if(!isActive()||attempt.questions().isEmpty())return;Toggle t=options.getSelectedToggle();if(t==null)return;char option=t==optionA?'A':t==optionB?'B':t==optionC?'C':'D';long questionId=current().id();answers.put(questionId,option);refreshReview();saveStatus.setText("Đang lưu…");fx.observe(api.saveAnswer(attempt.attemptId(),questionId,option),this::isActive,v->saveStatus.setText("Đã lưu"),e->saveStatus.setText("Chưa lưu được — hãy thử chọn lại"),()->{});}
    @FXML private void previous(){if(index>0){index--;render();}}
    @FXML private void next(){if(index+1<attempt.questions().size()){index++;render();}}
    private void refreshReview(){reviewGrid.getChildren().clear();for(int i=0;i<attempt.questions().size();i++){final int n=i;StudentQuestionDto q=attempt.questions().get(i);Button b=new Button(Integer.toString(i+1));b.setPrefSize(38,34);b.setStyle(answers.containsKey(q.id())?"-fx-background-color: #b7e4c7;":"-fx-background-color: #e5e7eb;");b.setOnAction(e->{index=n;render();});reviewGrid.add(b,i%8,i/8);}}
    private void updateTimer(){long m=seconds/60,s=seconds%60;timer.setText(String.format("%02d:%02d",m,s));timer.setStyle(seconds<60?"-fx-text-fill: #c0392b; -fx-font-size: 20px; -fx-font-weight: bold;":"-fx-font-size: 20px; -fx-font-weight: bold;");}
    @FXML private void manualSubmit(){submit(false);}
    private void submit(boolean automatic){if(!isActive()||submit.isDisabled())return;if(!automatic){Alert confirm=new Alert(Alert.AlertType.CONFIRMATION,"Bạn chắc chắn muốn nộp bài?",ButtonType.CANCEL,ButtonType.OK);confirm.setHeaderText("Nộp bài");if(confirm.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;}submit.setDisable(true);if(countdown!=null)countdown.stop();fx.observe(api.submitAttempt(attempt.attemptId()),this::isActive,navigator::showResult,e->{if(e instanceof quiz.client.api.ApiException){fx.observe(api.getMyResult(attempt.attemptId()),this::isActive,navigator::showResult,x->{saveStatus.setText("Không thể tải kết quả. Vui lòng thử lại từ lịch sử.");submit.setDisable(false);},()->{});}else{saveStatus.setText("Không xác nhận được việc nộp bài. Hãy kết nối lại để kiểm tra trạng thái.");submit.setDisable(false);}},()->{});}
    @Override protected void onDispose(){if(countdown!=null)countdown.stop();}
}
