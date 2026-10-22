package quiz.server.dispatch.handlers;

import quiz.common.dto.AddClassMemberDto;
import quiz.common.dto.CreateClassDto;
import quiz.common.enums.Role;
import quiz.common.protocol.ErrorCode;
import quiz.common.protocol.MessageType;
import quiz.common.protocol.Request;
import quiz.common.protocol.Response;
import quiz.server.dispatch.CommandHandler;
import quiz.server.exception.QuizException;
import quiz.server.network.UserSession;
import quiz.server.service.ClassService;

import java.util.Objects;
import java.util.Set;

/**
 * Xử lý các thông điệp quản lý lớp học (FR-A07).
 */
public final class ClassHandler implements CommandHandler {
    private final ClassService classService;

    public ClassHandler(ClassService classService) {
        this.classService = Objects.requireNonNull(classService, "ClassService cannot be null");
    }

    @Override
    public Set<MessageType> supportedTypes() {
        return Set.of(
            MessageType.CREATE_CLASS,
            MessageType.LIST_CLASSES,
            MessageType.ADD_CLASS_MEMBER,
            MessageType.REMOVE_CLASS_MEMBER,
            MessageType.LIST_CLASS_MEMBERS
        );
    }

    @Override
    public Set<Role> allowedRoles(MessageType type) {
        if (!supportedTypes().contains(type)) {
            throw new IllegalArgumentException("Unsupported class command");
        }
        return Set.of(Role.TEACHER, Role.ADMIN);
    }

    @Override
    public Response handle(Request request, UserSession session) {
        UserSession authSession = HandlerPayload.authenticated(session);
        return switch (request.type()) {
            case CREATE_CLASS -> {
                CreateClassDto dto = HandlerPayload.required(request, CreateClassDto.class);
                yield Response.success(request.requestId(), classService.createClass(dto, authSession));
            }
            case LIST_CLASSES -> {
                HandlerPayload.absent(request);
                yield Response.success(request.requestId(), classService.listClasses(authSession));
            }
            case ADD_CLASS_MEMBER -> {
                AddClassMemberDto dto = HandlerPayload.required(request, AddClassMemberDto.class);
                classService.addClassMember(dto.classId(), dto.studentId(), authSession);
                yield Response.success(request.requestId(), null);
            }
            case REMOVE_CLASS_MEMBER -> {
                AddClassMemberDto dto = HandlerPayload.required(request, AddClassMemberDto.class);
                classService.removeClassMember(dto.classId(), dto.studentId(), authSession);
                yield Response.success(request.requestId(), null);
            }
            case LIST_CLASS_MEMBERS -> {
                Long classId = HandlerPayload.required(request, Long.class);
                yield Response.success(request.requestId(), classService.listClassMembers(classId, authSession));
            }
            default -> throw new QuizException(ErrorCode.INVALID_REQUEST, "Unsupported class command");
        };
    }
}
