package quiz.server.dispatch.handlers;

import org.junit.jupiter.api.Test;
import quiz.common.dto.ExamSessionRequestDto;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import quiz.server.network.UserSession;
import quiz.server.service.MonitoringService;
import static org.mockito.Mockito.*;

class MonitoringHandlerTest {
    @Test void heartbeatPassesClientIpAndAuthenticatedStudentToService() {
        MonitoringService service=mock(MonitoringService.class);
        UserSession student=new UserSession(30,Role.STUDENT,"student");
        new MonitoringHandler(service).handle(new Request(1,MessageType.GET_EXAM_SESSION,"token",
            new ExamSessionRequestDto(10,"192.168.1.23")),student);
        verify(service).studentState(10,student,"192.168.1.23");
    }
}
