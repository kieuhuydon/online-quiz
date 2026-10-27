package quiz.server.repository;

import quiz.common.dto.ClassMemberDto;
import quiz.server.domain.Classroom;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for Classroom and Class Member enrollments.
 */
public interface ClassRepository {

    /**
     * Inserts a new classroom.
     *
     * @param classroom new classroom with id 0 and null createdAt
     * @return persisted classroom with database ID and timestamp
     * @throws ConflictException if class name is already in use
     * @throws QuizException if database error occurs
     */
    Classroom save(Classroom classroom);

    /** Deletes a class and its memberships/assignments atomically; false if it no longer exists. */
    boolean deleteById(long classId);

    /**
     * Finds a classroom by ID.
     */
    Optional<Classroom> findById(long id);

    /**
     * Finds a classroom by unique name.
     */
    Optional<Classroom> findByName(String name);

    /**
     * Returns all classrooms ordered by ID.
     */
    List<Classroom> findAll();

    /**
     * Returns classrooms managed by a given teacher.
     */
    List<Classroom> findByTeacherId(long teacherId);

    /**
     * Adds a student to a class.
     *
     * @throws ConflictException if student is already in the class
     */
    void addMember(long classId, long studentId);

    /**
     * Removes a student from a class.
     */
    void removeMember(long classId, long studentId);

    /**
     * Returns the enrolled students in a class with student names.
     */
    List<ClassMemberDto> findMembers(long classId);

    /**
     * Checks if a student is enrolled in a class.
     */
    boolean isMember(long classId, long studentId);

    /**
     * Finds all class IDs that a student is enrolled in.
     */
    List<Long> findClassIdsForStudent(long studentId);
}
