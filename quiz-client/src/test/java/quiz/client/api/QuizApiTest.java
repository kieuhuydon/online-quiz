package quiz.client.api;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quiz.client.network.*;
import quiz.client.support.SocketFixture;
import quiz.common.dto.*;
import quiz.common.enums.Role;
import quiz.common.protocol.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static quiz.client.support.SocketFixture.*;

@Timeout(15)
class QuizApiTest {
    @Test void heartbeatRunsOnlyInAuthenticatedSessionAndStopsOnLogout() throws Exception {
        try (var server = new SocketFixture()) {
            var base = server.config();
            var config = new quiz.client.config.ClientConfig(base.host(), base.port(), 1000, 1000, 1000, 16, 1000, 80);
            try (var api = new QuizApi(new ServerConnection(config))) {
                api.connect().get(3, TimeUnit.SECONDS);
                var peer = server.take();
                login(api, peer, Role.ADMIN);
                Request beat = peer.read();
                assertEquals(MessageType.HEARTBEAT, beat.type());
                assertEquals(TOKEN, beat.token());
                assertNull(beat.payload());
                // A delayed heartbeat cannot cause an unbounded queue of further heartbeats.
                Thread.sleep(250);
                var logout = api.logout();
                Request request = peer.read();
                assertEquals(MessageType.LOGOUT, request.type());
                peer.success(beat, null);
                peer.success(request, null);
                logout.get(3, TimeUnit.SECONDS);
                assertTrue(api.session().current().isEmpty());
                peer.socket.setSoTimeout(250);
                assertThrows(java.net.SocketTimeoutException.class, peer::read);
            }
        }
    }

    @Test void rejectedHeartbeatClearsSessionAndNotifiesOnce() throws Exception {
        try (var server = new SocketFixture()) {
            var base = server.config();
            var config = new quiz.client.config.ClientConfig(base.host(), base.port(), 1000, 1000, 1000, 16, 1000, 80);
            try (var api = new QuizApi(new ServerConnection(config))) {
                api.connect().get(3, TimeUnit.SECONDS);
                var peer = server.take();
                login(api, peer, Role.STUDENT);
                var events = new LinkedBlockingQueue<QuizApi.SessionLost>();
                api.addSessionLostListener(events::add);
                var beat = peer.read();
                peer.send(Response.error(beat.requestId(), ErrorCode.UNAUTHENTICATED, "revoked"));
                assertNotNull(events.poll(3, TimeUnit.SECONDS));
                assertTrue(api.session().current().isEmpty());
                assertTrue(events.isEmpty());
            }
        }
    }
    private static final String TOKEN = "secret-fixture-token";
    private static final AccountDto ACCOUNT = new AccountDto(8, "student", "Student", null, Role.STUDENT, true, 1L, Instant.EPOCH);

    @Test void fiveApiContractsAndReloginOnSameSocket() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long generation = api.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            login(api, peer, Role.ADMIN);
            assertEquals(Role.ADMIN, api.session().current().orElseThrow().role());
            assertFalse(api.session().current().orElseThrow().toString().contains(TOKEN));
            assertCode(ErrorCode.CONFLICT, failure(api.login("admin", "password")));
            var create = api.createAccount(new CreateAccountDto(" student ", " pass123 ", " Student ", " ", Role.STUDENT));
            Request request = peer.read();
            assertEquals(MessageType.CREATE_ACCOUNT, request.type()); assertEquals(TOKEN, request.token());
            var dto = assertInstanceOf(CreateAccountDto.class, request.payload());
            assertEquals("student", dto.username()); assertEquals(" pass123 ", dto.password()); assertNull(dto.email());
            peer.success(request, ACCOUNT); assertEquals(ACCOUNT, create.get(3, TimeUnit.SECONDS));
            var list = api.listAccounts(); request = peer.read();
            assertEquals(MessageType.LIST_ACCOUNTS, request.type()); assertNull(request.payload()); assertEquals(TOKEN, request.token());
            peer.success(request, new ArrayList<>(List.of(ACCOUNT)));
            var accounts = list.get(3, TimeUnit.SECONDS);
            assertEquals(List.of(ACCOUNT), accounts); assertThrows(UnsupportedOperationException.class, () -> accounts.clear());
            var active = api.setAccountActive(8, false); request = peer.read();
            assertEquals(MessageType.SET_ACCOUNT_ACTIVE, request.type()); assertEquals(TOKEN, request.token());
            assertEquals(new SetAccountActiveDto(8, false), request.payload());
            peer.success(request, null); assertNull(active.get(3, TimeUnit.SECONDS));
            var logout = api.logout(); request = peer.read();
            assertEquals(MessageType.LOGOUT, request.type()); assertEquals(TOKEN, request.token()); assertNull(request.payload());
            assertCode(ErrorCode.CONFLICT, failure(api.listAccounts()));
            peer.success(request, null); logout.get(3, TimeUnit.SECONDS);
            assertTrue(api.session().current().isEmpty()); assertTrue(transport.isConnected(generation));
            login(api, peer, Role.STUDENT);
            assertEquals(Role.STUDENT, api.session().current().orElseThrow().role());
        }
    }

    @ParameterizedTest @EnumSource(value = ErrorCode.class, names = {"INVALID_CREDENTIALS", "ACCOUNT_DISABLED", "SERVER_BUSY"})
    void failedLoginNeverStoresSessionAndCanRetry(ErrorCode error) throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var result = api.login("user", "password"); var request = peer.read();
            assertCode(ErrorCode.CONFLICT, failure(api.login("other", "password")));
            peer.send(Response.error(request.requestId(), error, "do not expose password or SQL"));
            Throwable failure = failure(result); assertCode(error, failure);
            assertFalse(failure.getMessage().contains("SQL")); assertTrue(api.session().current().isEmpty());
            login(api, peer, Role.TEACHER);
        }
    }

    @Test void forbiddenKeepsSessionButUnauthenticatedDisconnectsAndNotifiesOnce() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long old = api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.TEACHER);
            var events = new LinkedBlockingQueue<QuizApi.SessionLost>(); api.addSessionLostListener(events::add);
            var forbidden = api.listAccounts(); var request = peer.read();
            peer.send(Response.error(request.requestId(), ErrorCode.FORBIDDEN, "denied"));
            assertCode(ErrorCode.FORBIDDEN, failure(forbidden)); assertTrue(api.session().current().isPresent());
            var expired = api.listAccounts(); var another = api.listAccounts();
            request = peer.read(); peer.read();
            peer.send(Response.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "expired"));
            assertCode(ErrorCode.UNAUTHENTICATED, failure(expired));
            assertInstanceOf(TransportException.class, failure(another));
            var event = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(event); assertTrue(api.isCurrent(event)); assertTrue(events.isEmpty());
            assertTrue(api.session().current().isEmpty()); assertFalse(transport.isConnected(old));
            assertInstanceOf(TransportException.class, failure(api.login("user", "password")));
            long fresh = api.connect().get(3, TimeUnit.SECONDS); var replacement = server.take();
            login(api, replacement, Role.ADMIN);
            assertFalse(api.isCurrent(event));
            transport.disconnect(old, new TransportException(TransportException.Kind.CLOSED));
            assertEquals(fresh, api.session().current().orElseThrow().generation());
        }
    }

    @Test void malformedLoginDoesNotPublishSession() throws Exception {
        for (Serializable payload : new Serializable[] {"wrong", new LoginResultDto("", 1, "admin", "Admin", Role.ADMIN),
                new LoginResultDto(TOKEN, 0, "admin", "Admin", Role.ADMIN), new LoginResultDto(TOKEN, 1, "admin", "Admin", null)}) {
            try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
                api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
                var result = api.login("user", "password"); peer.success(peer.read(), payload);
                assertProtocol(failure(result)); assertTrue(api.session().current().isEmpty());
                assertEquals(ServerConnection.State.DISCONNECTED, transport.state());
            }
        }
    }

    @Test void malformedListAndVoidPayloadInvalidateConnection() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
                api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
                CompletableFuture<?> result = switch (variant) {
                    case 0 -> api.listAccounts(); case 1 -> api.setAccountActive(8, false); default -> api.logout();
                };
                peer.success(peer.read(), variant == 0 ? new ArrayList<>(List.of(ACCOUNT, "wrong")) : "not-null");
                assertProtocol(failure(result)); assertTrue(api.session().current().isEmpty());
            }
        }
    }

    @Test void logoutFailureStillDiscardsLocalSession() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
            var logout = api.logout(); var request = peer.read();
            peer.send(Response.error(request.requestId(), ErrorCode.SERVER_BUSY, "busy"));
            assertCode(ErrorCode.SERVER_BUSY, failure(logout)); assertTrue(api.session().current().isEmpty());
            assertEquals(ServerConnection.State.DISCONNECTED, transport.state());
        }
    }

    @Test void timeoutOfLoginCannotRestoreSessionAfterReconnect() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config(250, 8)); var api = new QuizApi(transport)) {
            long old = api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var result = api.login("old", "password"); var request = peer.read();
            assertInstanceOf(TransportException.class, failure(result)); assertTrue(api.session().current().isEmpty());
            long fresh = api.connect().get(3, TimeUnit.SECONDS); var newPeer = server.take();
            login(api, newPeer, Role.STUDENT);
            try { peer.success(request, new LoginResultDto("old", 99, "old", "Old", Role.ADMIN)); }
            catch (java.io.IOException expectedClosedSocket) { assertFalse(transport.isConnected(old)); }
            assertFalse(transport.isConnected(old));
            assertEquals(fresh, api.session().current().orElseThrow().generation());
            assertEquals(Role.STUDENT, api.session().current().orElseThrow().role());
        }
    }

    @Test void oldAccountResponseIsRejectedAfterLogoutStarts() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
            var list = api.listAccounts(); var listRequest = peer.read();
            var logout = api.logout(); var logoutRequest = peer.read();
            peer.success(listRequest, new ArrayList<>(List.of(ACCOUNT)));
            assertInstanceOf(TransportException.class, failure(list));
            peer.success(logoutRequest, null); logout.get(3, TimeUnit.SECONDS);
            login(api, peer, Role.TEACHER);
        }
    }

    @Test void cancellingLoginOrWriteClosesSocketAndNeverRetries() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS); var peer = server.take();
            var login = api.login("user", "password"); peer.read(); assertTrue(login.cancel(false));
            assertEquals(ServerConnection.State.DISCONNECTED, transport.state()); assertTrue(api.session().current().isEmpty());
            api.connect().get(3, TimeUnit.SECONDS); peer = server.take(); login(api, peer, Role.ADMIN);
            var write = api.setAccountActive(8, false); peer.read(); assertTrue(write.cancel(false));
            assertTrue(api.session().current().isEmpty()); assertEquals(ServerConnection.State.DISCONNECTED, transport.state());
        }
    }

    @Test void disconnectedSessionIsImmediatelyHiddenAndApiCloseIsIdempotent() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long generation = api.connect().get(3, TimeUnit.SECONDS); var peer = server.take(); login(api, peer, Role.ADMIN);
            transport.disconnect(generation, new TransportException(TransportException.Kind.CONNECTION_LOST));
            assertTrue(api.session().current().isEmpty());
            assertCode(ErrorCode.UNAUTHENTICATED, failure(api.listAccounts()));
            api.close(); api.close(); assertInstanceOf(TransportException.class, failure(api.connect()));
        }
    }

    private static void login(QuizApi api, SocketFixture.Peer peer, Role role) throws Exception {
        var future = api.login(" admin ", " password ");
        Request request = peer.read(); assertEquals(MessageType.LOGIN, request.type()); assertNull(request.token());
        assertEquals(new LoginRequestDto("admin", " password "), request.payload());
        var login = new LoginResultDto(TOKEN, 1, "admin", "Admin", role);
        peer.success(request, login); assertEquals(login, future.get(3, TimeUnit.SECONDS));
    }

    private static void assertCode(ErrorCode code, Throwable failure) {
        assertEquals(code, assertInstanceOf(ApiException.class, failure).errorCode());
    }
    private static void assertProtocol(Throwable failure) {
        assertEquals(TransportException.Kind.PROTOCOL, assertInstanceOf(TransportException.class, failure).kind());
    }

    @Test void resetLoginConnectionDisconnectsUnauthenticatedButRejectsAuthenticatedSession() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            long gen1 = api.connect().get(3, TimeUnit.SECONDS);
            var peer1 = server.take();
            assertTrue(transport.isConnected(gen1));
            api.resetLoginConnection();
            assertFalse(transport.isConnected(gen1));

            api.connect().get(3, TimeUnit.SECONDS);
            var peer2 = server.take();
            login(api, peer2, Role.ADMIN);
            assertCode(ErrorCode.CONFLICT, assertThrows(ApiException.class, api::resetLoginConnection));
        }
    }

    @Test void classQuestionQuizAttemptAndAntiCheatContractsRoundTrip() throws Exception {
        try (var server = new SocketFixture(); var transport = new ServerConnection(server.config()); var api = new QuizApi(transport)) {
            api.connect().get(3, TimeUnit.SECONDS);
            var peer = server.take();
            login(api, peer, Role.ADMIN);

            Instant now = Instant.EPOCH;

            // 1. Class contracts
            ClassDto classDto = new ClassDto(10L, "ENG-01", "Desc", 2L, "Teacher", now);
            var createClass = api.createClass(new CreateClassDto("ENG-01", "Desc", 2L));
            Request req = peer.read();
            assertEquals(MessageType.CREATE_CLASS, req.type());
            peer.success(req, classDto);
            assertEquals(classDto, createClass.get(3, TimeUnit.SECONDS));

            var listClasses = api.listClasses();
            req = peer.read();
            assertEquals(MessageType.LIST_CLASSES, req.type());
            peer.success(req, new ArrayList<>(List.of(classDto)));
            assertEquals(List.of(classDto), listClasses.get(3, TimeUnit.SECONDS));

            var listMyClasses = api.listMyClasses();
            req = peer.read();
            assertEquals(MessageType.LIST_MY_CLASSES, req.type());
            peer.success(req, new ArrayList<>(List.of(classDto)));
            assertEquals(List.of(classDto), listMyClasses.get(3, TimeUnit.SECONDS));

            var addMember = api.addClassMember(10L, 8L);
            req = peer.read();
            assertEquals(MessageType.ADD_CLASS_MEMBER, req.type());
            peer.success(req, null);
            assertNull(addMember.get(3, TimeUnit.SECONDS));

            var removeMember = api.removeClassMember(10L, 8L);
            req = peer.read();
            assertEquals(MessageType.REMOVE_CLASS_MEMBER, req.type());
            peer.success(req, null);
            assertNull(removeMember.get(3, TimeUnit.SECONDS));

            ClassMemberDto memberDto = new ClassMemberDto(10L, 8L, "student", "Student", now);
            var listMembers = api.listClassMembers(10L);
            req = peer.read();
            assertEquals(MessageType.LIST_CLASS_MEMBERS, req.type());
            peer.success(req, new ArrayList<>(List.of(memberDto)));
            assertEquals(List.of(memberDto), listMembers.get(3, TimeUnit.SECONDS));

            var listUnenrolled = api.listUnenrolledStudents();
            req = peer.read();
            assertEquals(MessageType.LIST_UNENROLLED_STUDENTS, req.type());
            peer.success(req, new ArrayList<>(List.of(ACCOUNT)));
            assertEquals(List.of(ACCOUNT), listUnenrolled.get(3, TimeUnit.SECONDS));

            var delClass = api.deleteClass(10L);
            req = peer.read();
            assertEquals(MessageType.DELETE_CLASS, req.type());
            peer.success(req, null);
            assertNull(delClass.get(3, TimeUnit.SECONDS));

            // 2. Question contracts
            QuestionDto qDto = new QuestionDto(5L, "Q?", "A", "B", "C", "D", 'A', "Topic", quiz.common.enums.Difficulty.EASY, true, 1L, now);
            var createQ = api.createQuestion(new CreateQuestionDto("Q?", "A", "B", "C", "D", 'A', "Topic", quiz.common.enums.Difficulty.EASY, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "Giải thích"));
            req = peer.read();
            assertEquals(MessageType.CREATE_QUESTION, req.type());
            peer.success(req, qDto);
            assertEquals(qDto, createQ.get(3, TimeUnit.SECONDS));

            var getQ = api.getQuestion(5L);
            req = peer.read();
            assertEquals(MessageType.GET_QUESTION, req.type());
            peer.success(req, qDto);
            assertEquals(qDto, getQ.get(3, TimeUnit.SECONDS));

            var listQ = api.listQuestions();
            req = peer.read();
            assertEquals(MessageType.LIST_QUESTIONS, req.type());
            peer.success(req, new ArrayList<>(List.of(qDto)));
            assertEquals(List.of(qDto), listQ.get(3, TimeUnit.SECONDS));

            var updateQ = api.updateQuestion(new UpdateQuestionDto(5L, "Q?", "A", "B", "C", "D", 'B', "Topic", quiz.common.enums.Difficulty.MEDIUM, quiz.common.enums.QuestionType.MULTIPLE_CHOICE, "Giải thích"));
            req = peer.read();
            assertEquals(MessageType.UPDATE_QUESTION, req.type());
            peer.success(req, qDto);
            assertEquals(qDto, updateQ.get(3, TimeUnit.SECONDS));

            var deactQ = api.deactivateQuestion(5L);
            req = peer.read();
            assertEquals(MessageType.DEACTIVATE_QUESTION, req.type());
            peer.success(req, null);
            assertNull(deactQ.get(3, TimeUnit.SECONDS));

            // 3. Quiz contracts
            QuizDto quizDto = new QuizDto(1L, "Quiz", "Desc", "Cat", 30, 50.0, quiz.common.enums.QuizStatus.OPEN, "123456",
                quiz.common.enums.AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3, 1L, now, now, null);
            var createQuiz = api.createQuiz(new CreateQuizDto("Quiz", "Desc", "Cat", 30, 50.0, quiz.common.enums.AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3));
            req = peer.read();
            assertEquals(MessageType.CREATE_QUIZ, req.type());
            peer.success(req, quizDto);
            assertEquals(quizDto, createQuiz.get(3, TimeUnit.SECONDS));

            var updateQuiz = api.updateQuiz(new UpdateQuizDto(1L, "Quiz", "Desc", "Cat", 30, 50.0, quiz.common.enums.AntiCheatPolicyType.WARN_THEN_AUTO_SUBMIT, 3));
            req = peer.read();
            assertEquals(MessageType.UPDATE_QUIZ, req.type());
            peer.success(req, quizDto);
            assertEquals(quizDto, updateQuiz.get(3, TimeUnit.SECONDS));

            QuizDetailDto detailDto = new QuizDetailDto(quizDto, List.of(qDto), List.of(10L));
            var getQuiz = api.getQuiz(1L);
            req = peer.read();
            assertEquals(MessageType.GET_QUIZ, req.type());
            peer.success(req, detailDto);
            assertEquals(detailDto, getQuiz.get(3, TimeUnit.SECONDS));

            var listQuizzes = api.listQuizzes();
            req = peer.read();
            assertEquals(MessageType.LIST_QUIZZES, req.type());
            peer.success(req, new ArrayList<>(List.of(quizDto)));
            assertEquals(List.of(quizDto), listQuizzes.get(3, TimeUnit.SECONDS));

            var setQs = api.setQuizQuestions(1L, List.of(5L));
            req = peer.read();
            assertEquals(MessageType.SET_QUIZ_QUESTIONS, req.type());
            peer.success(req, null);
            assertNull(setQs.get(3, TimeUnit.SECONDS));

            var openQuiz = api.openQuiz(1L);
            req = peer.read();
            assertEquals(MessageType.OPEN_QUIZ, req.type());
            peer.success(req, quizDto);
            assertEquals(quizDto, openQuiz.get(3, TimeUnit.SECONDS));

            var closeQuiz = api.closeQuiz(1L);
            req = peer.read();
            assertEquals(MessageType.CLOSE_QUIZ, req.type());
            peer.success(req, quizDto);
            assertEquals(quizDto, closeQuiz.get(3, TimeUnit.SECONDS));

            var assignQuiz = api.assignQuiz(1L, 10L);
            req = peer.read();
            assertEquals(MessageType.ASSIGN_QUIZ, req.type());
            peer.success(req, null);
            assertNull(assignQuiz.get(3, TimeUnit.SECONDS));

            ResultDto resDto = new ResultDto(100L, 1L, "Quiz", "Student", 9.0, 9, 10, true, quiz.common.enums.SubmitReason.MANUAL, now);
            var listQuizResults = api.listQuizResults(1L);
            req = peer.read();
            assertEquals(MessageType.LIST_QUIZ_RESULTS, req.type());
            peer.success(req, new ArrayList<>(List.of(resDto)));
            assertEquals(List.of(resDto), listQuizResults.get(3, TimeUnit.SECONDS));

            ClassQuizResultsDto classQuizResults = new ClassQuizResultsDto(List.of(quizDto), List.of(resDto));
            var getClassResults = api.getClassQuizResults(10L);
            req = peer.read();
            assertEquals(MessageType.GET_CLASS_QUIZ_RESULTS, req.type());
            peer.success(req, classQuizResults);
            assertEquals(classQuizResults, getClassResults.get(3, TimeUnit.SECONDS));

            // 4. Student Attempt & Result contracts
            QuizInfoDto infoDto = new QuizInfoDto(1L, "Quiz", "Desc", "Cat", 30, 50.0, 1, quiz.common.enums.QuizStatus.OPEN, "123456", true, now, null);
            var listAssigned = api.listAssignedQuizzes();
            req = peer.read();
            assertEquals(MessageType.LIST_ASSIGNED_QUIZZES, req.type());
            peer.success(req, new ArrayList<>(List.of(infoDto)));
            assertEquals(List.of(infoDto), listAssigned.get(3, TimeUnit.SECONDS));

            var getInfo = api.getQuizInfo(1L);
            req = peer.read();
            assertEquals(MessageType.GET_QUIZ_INFO, req.type());
            peer.success(req, infoDto);
            assertEquals(infoDto, getInfo.get(3, TimeUnit.SECONDS));

            AttemptStateDto stateDto = new AttemptStateDto(100L, 1L, "Quiz", 1800L, List.of(), Map.of());
            var startAtt = api.startAttempt(1L, "123456");
            req = peer.read();
            assertEquals(MessageType.START_ATTEMPT, req.type());
            peer.success(req, stateDto);
            assertEquals(stateDto, startAtt.get(3, TimeUnit.SECONDS));

            var saveAns = api.saveAnswer(100L, 5L, 'A');
            req = peer.read();
            assertEquals(MessageType.SAVE_ANSWER, req.type());
            peer.success(req, null);
            assertNull(saveAns.get(3, TimeUnit.SECONDS));

            var getState = api.getAttemptState(100L);
            req = peer.read();
            assertEquals(MessageType.GET_ATTEMPT_STATE, req.type());
            peer.success(req, stateDto);
            assertEquals(stateDto, getState.get(3, TimeUnit.SECONDS));

            var submitAtt = api.submitAttempt(100L);
            req = peer.read();
            assertEquals(MessageType.SUBMIT_ATTEMPT, req.type());
            peer.success(req, resDto);
            assertEquals(resDto, submitAtt.get(3, TimeUnit.SECONDS));

            var getMyRes = api.getMyResult(100L);
            req = peer.read();
            assertEquals(MessageType.GET_MY_RESULT, req.type());
            peer.success(req, resDto);
            assertEquals(resDto, getMyRes.get(3, TimeUnit.SECONDS));

            var listHist = api.listMyHistory();
            req = peer.read();
            assertEquals(MessageType.LIST_MY_HISTORY, req.type());
            peer.success(req, new ArrayList<>(List.of(resDto)));
            assertEquals(List.of(resDto), listHist.get(3, TimeUnit.SECONDS));

            // 5. Anti-cheat contracts
            var reportAc = api.reportAntiCheat(new ReportAntiCheatDto(100L, quiz.common.enums.AntiCheatEventType.FOCUS_LOST, "blur", now));
            req = peer.read();
            assertEquals(MessageType.REPORT_ANTICHEAT_EVENT, req.type());
            peer.success(req, null);
            assertNull(reportAc.get(3, TimeUnit.SECONDS));

            AntiCheatEventDto acEvent = new AntiCheatEventDto(1L, 100L, quiz.common.enums.AntiCheatEventType.FOCUS_LOST, "blur", now, now, 1, "WARNED");
            var listAc = api.listAttemptEvents(100L);
            req = peer.read();
            assertEquals(MessageType.LIST_ATTEMPT_EVENTS, req.type());
            peer.success(req, new ArrayList<>(List.of(acEvent)));
            assertEquals(List.of(acEvent), listAc.get(3, TimeUnit.SECONDS));
        }
    }
}
