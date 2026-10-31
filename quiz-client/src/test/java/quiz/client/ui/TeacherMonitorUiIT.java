package quiz.client.ui;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import quiz.client.api.QuizApi;
import quiz.client.anticheat.StudentAntiCheatService;
import quiz.client.network.ServerConnection;
import quiz.client.support.SocketFixture;
import quiz.client.ui.teacher.TeacherMonitorController;
import quiz.client.util.FxAsync;
import quiz.common.dto.*;
import quiz.common.enums.*;
import quiz.common.protocol.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class TeacherMonitorUiIT {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        try {Platform.startup(started::countDown);} catch(IllegalStateException alreadyStarted) {started.countDown();}
        assertTrue(started.await(5,TimeUnit.SECONDS));Platform.setImplicitExit(false);
    }
    @Test void teacherTableRendersAndSendsPenaltyForSelectedStudent() throws Exception {
        try(var server=new SocketFixture()) {
            var base=server.config();
            var config=new quiz.client.config.ClientConfig(base.host(),base.port(),1000,1000,3000,16,1000,60000,60000);
            try(var api=new QuizApi(new ServerConnection(config))) {
                api.connect().get(3,TimeUnit.SECONDS);var peer=server.take();login(api,peer,Role.TEACHER);
                var controller=fx(()->{var c=new TeacherMonitorController(api,new FxAsync(),20,"Java",null,()->true);c.show();return c;});
                try {
                    var request=peer.read();assertEquals(MessageType.LIST_EXAM_SESSIONS,request.type());
                    peer.success(request,new ArrayList<>(List.of(state(0,false))));
                    await(()->table().getItems().size()==1);
                    fx(()->{
                        var table=table();assertEquals("127.0.0.1",((ExamSession)table.getItems().getFirst()).ipAddress());
                        table.getSelectionModel().select(0);table.applyCss();table.layout();
                        assertTrue(table.lookupAll(".table-row-cell").stream().anyMatch(n->n.getStyle().contains("fecaca")));
                        Platform.runLater(()->{
                            DialogPane form=(DialogPane)Window.getWindows().stream().filter(w->w.isShowing()&&w.getScene().getRoot() instanceof DialogPane)
                                .map(w->w.getScene().getRoot()).filter(n->((DialogPane)n).getHeaderText().contains("Trừ điểm")).findFirst().orElseThrow();
                            form.lookupAll(".text-field").stream().map(n->(TextField)n).filter(t->!t.getPromptText().isEmpty()).forEach(t->t.setText("Discord"));
                            ((Button)form.lookupButton(ButtonType.OK)).fire();
                        });
                        ((Button)table.getScene().lookup("#monitorScore")).fire();return null;
                    });
                    request=peer.read();assertEquals(MessageType.APPLY_MONITORING_PENALTY,request.type());
                    ApplyPenaltyDto p=assertInstanceOf(ApplyPenaltyDto.class,request.payload());
                    assertEquals(10,p.sessionId());assertEquals(1,p.amount());assertEquals("Discord",p.reason());
                    peer.success(request,state(1,false));
                    await(()->((ExamSession)table().getItems().getFirst()).deductedScore()==1);
                } finally {fx(()->{controller.close();return null;});}
            }
        }
    }
    @Test void studentReceivesTeacherDecisionOnFxThreadAndIgnoresLateResponseAfterClose() throws Exception {
        try(var server=new SocketFixture();var api=new QuizApi(new ServerConnection(server.config()))) {
            api.connect().get(3,TimeUnit.SECONDS);var peer=server.take();login(api,peer,Role.STUDENT);
            BlockingQueue<ExamSession> updates=new LinkedBlockingQueue<>();
            try(var service=new StudentAntiCheatService()) {
                service.start(api,10,s->{assertTrue(Platform.isFxApplicationThread());updates.add(s);},e->fail(e));
                Request request=peer.read();assertEquals(MessageType.GET_EXAM_SESSION,request.type());
                peer.success(request,state(2,true));
                ExamSession result=updates.poll(3,TimeUnit.SECONDS);assertNotNull(result);assertTrue(result.forceStopped());assertEquals(2,result.deductedScore());
            }
            try(var closedService=new StudentAntiCheatService()) {
                closedService.start(api,10,updates::add,e->fail(e));Request pending=peer.read();closedService.close();
                peer.success(pending,state(3,false));assertNull(updates.poll(300,TimeUnit.MILLISECONDS));
            }
        }
    }
    private static ExamSession state(double deduction,boolean stopped) {
        return new ExamSession(10,20,30,"Nguyễn Văn A","127.0.0.1",stopped?MonitoringStatus.TERMINATED:MonitoringStatus.CHEATING,
            2,"Discord",deduction,300,stopped,Instant.now(),stopped?0:300);
    }
    private static void login(QuizApi api,SocketFixture.Peer peer,Role role) throws Exception {
        var login=api.login("tester","password");peer.success(peer.read(),new LoginResultDto("token",1,"tester","Tester",role));login.get(3,TimeUnit.SECONDS);
    }
    private static TableView<?> table() {
        return (TableView<?>)Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().lookup("#monitorTable"))
            .filter(Objects::nonNull).findFirst().orElseThrow();
    }
    private static void await(Callable<Boolean> check) throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<limit) {if(fx(check))return;Thread.sleep(10);}
        fail("UI did not update");
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action);Platform.runLater(task);return task.get(5,TimeUnit.SECONDS);
    }
}
