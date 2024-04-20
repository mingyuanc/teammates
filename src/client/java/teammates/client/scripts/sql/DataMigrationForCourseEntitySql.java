package teammates.client.scripts.sql;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.googlecode.objectify.cmd.Query;

import teammates.common.datatransfer.InstructorPermissionRole;
import teammates.common.datatransfer.InstructorPrivileges;
import teammates.common.datatransfer.InstructorPrivilegesLegacy;
import teammates.common.datatransfer.questions.FeedbackQuestionDetails;
import teammates.common.datatransfer.questions.FeedbackResponseDetails;
import teammates.common.util.JsonUtils;
import teammates.storage.entity.Course;
import teammates.storage.entity.CourseStudent;
import teammates.storage.entity.DeadlineExtension;
import teammates.storage.entity.FeedbackQuestion;
import teammates.storage.entity.FeedbackResponse;
import teammates.storage.entity.FeedbackResponseComment;
import teammates.storage.entity.FeedbackSession;
import teammates.storage.entity.Instructor;
import teammates.storage.sqlentity.Section;
import teammates.storage.sqlentity.Student;
import teammates.storage.sqlentity.Team;
import teammates.storage.sqlentity.User;
import teammates.storage.sqlentity.questions.FeedbackConstantSumQuestion.FeedbackConstantSumQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackContributionQuestion.FeedbackContributionQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackMcqQuestion.FeedbackMcqQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackMsqQuestion.FeedbackMsqQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackNumericalScaleQuestion.FeedbackNumericalScaleQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackRankOptionsQuestion.FeedbackRankOptionsQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackRankRecipientsQuestion.FeedbackRankRecipientsQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackRubricQuestion.FeedbackRubricQuestionDetailsConverter;
import teammates.storage.sqlentity.questions.FeedbackTextQuestion.FeedbackTextQuestionDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackConstantSumResponse.FeedbackConstantSumResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackContributionResponse.FeedbackContributionResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackMsqResponse.FeedbackMsqResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackNumericalScaleResponse.FeedbackNumericalScaleResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackRankOptionsResponse.FeedbackRankOptionsResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackRankRecipientsResponse.FeedbackRankRecipientsResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackRubricResponse.FeedbackRubricResponseDetailsConverter;
import teammates.storage.sqlentity.responses.FeedbackTextResponse.FeedbackTextResponseDetailsConverter;

/**
 * Data migration class for course entity.
 */
@SuppressWarnings({ "PMD", "deprecation" })
public class DataMigrationForCourseEntitySql extends
        DataMigrationEntitiesBaseScriptSql<teammates.storage.entity.Course, teammates.storage.sqlentity.BaseEntity> {

    private static final int MAX_RESPONSE_COUNT = -1;

    public static void main(String[] args) {
        new DataMigrationForCourseEntitySql().doOperationRemotely();
    }

    @Override
    protected Query<Course> getFilterQuery() {
        return ofy().load().type(teammates.storage.entity.Course.class);
    }

    @Override
    protected boolean isPreview() {
        return false;
    }

    /*
     * Sets the migration criteria used in isMigrationNeeded.
     */
    @Override
    protected void setMigrationCriteria() {
        // No migration criteria currently needed.
    }

    @Override
    protected boolean isMigrationNeeded(Course entity) {
        return true;
    }

    @Override
    protected void migrateEntity(Course oldCourse) throws Exception {
        teammates.storage.sqlentity.Course newCourse = createCourse(oldCourse);
        // TODO: add shutdown hook to save the entity
        // Runnable shutdownScript = () -> { cascadeDelete(newCourse)};
        // Runtime.getRuntime().addShutdownHook(new Thread(shutdownScript));

        migrateCourseEntity(newCourse);
        // verifyCourseEntity(newCourse);
        // markOldCourseAsMigrated(courseId)
        // Runtime.getRuntime().removeShutDownHook(new Thread(shutdownScript));
    }

    private void migrateCourseEntity(teammates.storage.sqlentity.Course newCourse) {
        Map<String, User> userEmailToUserMap = new HashMap<>();
        Map<String, Section> sectionNameToSectionMap = migrateSectionChain(newCourse, userEmailToUserMap);
        Map<String, teammates.storage.sqlentity.FeedbackSession> feedbackSessionNameToFeedbackSessionMap =
                migrateFeedbackChain(newCourse, sectionNameToSectionMap);
        migrateInstructorEntities(newCourse, userEmailToUserMap);
        migrateDeadlineExtensionEntities(newCourse, feedbackSessionNameToFeedbackSessionMap, userEmailToUserMap);
    }

    // methods for migrate section chain ----------------------------------------------------------------------------------

    private Map<String, teammates.storage.sqlentity.Section> migrateSectionChain(
            teammates.storage.sqlentity.Course newCourse, Map<String, User> userEmailToUserMap) {
        List<CourseStudent> oldStudents = ofy().load().type(CourseStudent.class).filter("courseId", newCourse.getId())
                .list();
        Map<String, teammates.storage.sqlentity.Section> sections = new HashMap<>();
        Map<String, List<CourseStudent>> sectionToStuMap = oldStudents.stream()
                .collect(Collectors.groupingBy(CourseStudent::getSectionName));

        for (Map.Entry<String, List<CourseStudent>> entry : sectionToStuMap.entrySet()) {
            String sectionName = entry.getKey();
            List<CourseStudent> stuList = entry.getValue();
            teammates.storage.sqlentity.Section newSection = createSection(newCourse, sectionName);
            sections.put(sectionName, newSection);
            saveEntityDeferred(newSection);
            migrateTeams(newCourse, newSection, stuList, userEmailToUserMap);
        }
        return sections;
    }

    private void migrateTeams(teammates.storage.sqlentity.Course newCourse,
            teammates.storage.sqlentity.Section newSection, List<CourseStudent> studentsInSection,
            Map<String, User> userEmailToUserMap) {
        Map<String, List<CourseStudent>> teamNameToStuMap = studentsInSection.stream()
                .collect(Collectors.groupingBy(CourseStudent::getTeamName));
        for (Map.Entry<String, List<CourseStudent>> entry : teamNameToStuMap.entrySet()) {
            String teamName = entry.getKey();
            List<CourseStudent> stuList = entry.getValue();
            teammates.storage.sqlentity.Team newTeam = createTeam(newSection, teamName);
            saveEntityDeferred(newTeam);
            migrateStudents(newCourse, newTeam, stuList, userEmailToUserMap);
        }
    }

    private void migrateStudents(teammates.storage.sqlentity.Course newCourse, teammates.storage.sqlentity.Team newTeam,
            List<CourseStudent> studentsInTeam, Map<String, User> userEmailToUserMap) {
        for (CourseStudent oldStudent : studentsInTeam) {
            teammates.storage.sqlentity.Student newStudent = createStudent(newCourse, newTeam, oldStudent);
            saveEntityDeferred(newStudent);
            userEmailToUserMap.put(oldStudent.getEmail(), newStudent);
        }
    }

    private teammates.storage.sqlentity.Course createCourse(Course oldCourse) {
        teammates.storage.sqlentity.Course newCourse = new teammates.storage.sqlentity.Course(
                oldCourse.getUniqueId(),
                oldCourse.getName(),
                oldCourse.getTimeZone(),
                oldCourse.getInstitute());
        newCourse.setDeletedAt(oldCourse.getDeletedAt());
        newCourse.setCreatedAt(oldCourse.getCreatedAt());

        saveEntityDeferred(newCourse);
        return newCourse;
    }

    private teammates.storage.sqlentity.Section createSection(teammates.storage.sqlentity.Course newCourse,
            String sectionName) {
        // if (sectionName.equals(Const.DEFAULT_SECTION)) {
        // return Const.DEFAULT_SQL_SECTION;
        // }
        Section newSection = new Section(newCourse, sectionName);
        newSection.setCreatedAt(Instant.now());
        return newSection;
    }

    private teammates.storage.sqlentity.Team createTeam(teammates.storage.sqlentity.Section section, String teamName) {
        Team newTeam = new teammates.storage.sqlentity.Team(section, teamName);
        newTeam.setCreatedAt(Instant.now());
        return newTeam;
    }

    private Student createStudent(teammates.storage.sqlentity.Course newCourse,
            teammates.storage.sqlentity.Team newTeam,
            CourseStudent oldStudent) {
        Student newStudent = new Student(newCourse, oldStudent.getName(), oldStudent.getEmail(),
                oldStudent.getComments(), newTeam);
        newStudent.setUpdatedAt(oldStudent.getUpdatedAt());
        newStudent.setRegKey(oldStudent.getRegistrationKey());
        newStudent.setCreatedAt(oldStudent.getCreatedAt());

        return newStudent;
    }

    // methods for migrate feedback chain ---------------------------------------------------------------------------------

    private Map<String, teammates.storage.sqlentity.FeedbackSession> migrateFeedbackChain(
            teammates.storage.sqlentity.Course newCourse,
            Map<String, Section> sectionNameToSectionMap) {

        Map<String, teammates.storage.sqlentity.FeedbackSession> feedbackSessionNameToFeedbackSessionMap =
                new HashMap<>();

        List<FeedbackSession> oldSessions = ofy().load().type(FeedbackSession.class)
                .filter("courseId", newCourse.getId()).list();

        Map<String, List<FeedbackQuestion>> sessionNameToQuestionsMap = ofy().load().type(FeedbackQuestion.class)
                .filter("courseId", newCourse.getId()).list().stream()
                .collect(Collectors.groupingBy(FeedbackQuestion::getFeedbackSessionName));

        for (FeedbackSession oldSession : oldSessions) {
            teammates.storage.sqlentity.FeedbackSession newSession = migrateFeedbackSession(newCourse, oldSession,
                    sessionNameToQuestionsMap, sectionNameToSectionMap);
            feedbackSessionNameToFeedbackSessionMap.put(oldSession.getFeedbackSessionName(), newSession);
        }
        return feedbackSessionNameToFeedbackSessionMap;
    }

    private teammates.storage.sqlentity.FeedbackSession migrateFeedbackSession(
            teammates.storage.sqlentity.Course newCourse, FeedbackSession oldSession,
            Map<String, List<FeedbackQuestion>> sessionNameToQuestionsMap,
            Map<String, Section> sectionNameToSectionMap) {
        teammates.storage.sqlentity.FeedbackSession newSession = createFeedbackSession(newCourse, oldSession);
        saveEntityDeferred(newSession);

        Map<String, List<FeedbackResponse>> questionIdToResponsesMap;
        Query<FeedbackResponse> responsesInSession = ofy().load().type(FeedbackResponse.class)
                .filter("courseId", newCourse.getId())
                .filter("feedbackSessionName", oldSession.getFeedbackSessionName());
        if (responsesInSession.count() <= MAX_RESPONSE_COUNT) {
            questionIdToResponsesMap = responsesInSession.list().stream()
                    .collect(Collectors.groupingBy(FeedbackResponse::getFeedbackQuestionId));
        } else {
            questionIdToResponsesMap = null;
        }

        // cascade migrate questions
        List<FeedbackQuestion> oldQuestions = sessionNameToQuestionsMap.get(oldSession.getFeedbackSessionName());
        for (FeedbackQuestion oldQuestion : oldQuestions) {
            migrateFeedbackQuestion(newSession, oldQuestion, questionIdToResponsesMap, sectionNameToSectionMap);
        }
        return newSession;
    }

    private void migrateFeedbackQuestion(teammates.storage.sqlentity.FeedbackSession newSession,
            FeedbackQuestion oldQuestion, Map<String, List<FeedbackResponse>> questionIdToResponsesMap,
            Map<String, Section> sectionNameToSectionMap) {
        teammates.storage.sqlentity.FeedbackQuestion newFeedbackQuestion = createFeedbackQuestion(newSession,
                oldQuestion);
        saveEntityDeferred(newFeedbackQuestion);

        Map<String, List<FeedbackResponseComment>> responseIdToCommentsMap = ofy().load()
                .type(FeedbackResponseComment.class)
                .filter("courseId", newSession.getCourse().getId())
                .filter("feedbackSessionName", newSession.getName())
                .filter("feedbackQuestionId", oldQuestion.getId()).list().stream()
                .collect(Collectors.groupingBy(FeedbackResponseComment::getFeedbackResponseId));

        // cascade migrate responses
        List<FeedbackResponse> oldResponses;
        if (questionIdToResponsesMap != null) {
            oldResponses = questionIdToResponsesMap.get(oldQuestion.getId());
        } else {
            oldResponses = ofy().load().type(FeedbackResponse.class)
                    .filter("courseId", newSession.getCourse().getId())
                    .filter("feedbackSessionName", newSession.getName())
                    .filter("feedbackQuestionId", oldQuestion.getId()).list();
        }
        for (FeedbackResponse oldResponse : oldResponses) {
            Section newGiverSection = sectionNameToSectionMap.get(oldResponse.getGiverSection());
            Section newRecipientSection = sectionNameToSectionMap.get(oldResponse.getRecipientSection());
            migrateFeedbackResponse(newFeedbackQuestion, oldResponse, newGiverSection,
                    newRecipientSection, responseIdToCommentsMap);
        }
    }

    private void migrateFeedbackResponse(teammates.storage.sqlentity.FeedbackQuestion newQuestion,
            FeedbackResponse oldResponse, Section newGiverSection, Section newRecipientSection,
            Map<String, List<FeedbackResponseComment>> responseIdToCommentsMap) {
        teammates.storage.sqlentity.FeedbackResponse newResponse = createFeedbackResponse(newQuestion, oldResponse,
                newGiverSection, newRecipientSection);
        saveEntityDeferred(newResponse);

        // cascade migrate response comments
        List<FeedbackResponseComment> oldComments = responseIdToCommentsMap.get(oldResponse.getId());
        for (FeedbackResponseComment oldComment : oldComments) {
            migrateFeedbackResponseComment(newResponse, oldComment, newGiverSection, newRecipientSection);
        }
    }

    private void migrateFeedbackResponseComment(teammates.storage.sqlentity.FeedbackResponse newResponse,
            FeedbackResponseComment oldComment, Section newGiverSection, Section newRecipientSection) {
        teammates.storage.sqlentity.FeedbackResponseComment newComment = createFeedbackResponseComment(newResponse,
                oldComment, newGiverSection, newRecipientSection);
        saveEntityDeferred(newComment);
    }

    private teammates.storage.sqlentity.FeedbackSession createFeedbackSession(teammates.storage.sqlentity.Course newCourse,
            FeedbackSession oldSession) {
        teammates.storage.sqlentity.FeedbackSession newSession = new teammates.storage.sqlentity.FeedbackSession(
                oldSession.getFeedbackSessionName(),
                newCourse,
                oldSession.getCreatorEmail(),
                oldSession.getInstructions(),
                oldSession.getStartTime(),
                oldSession.getEndTime(),
                oldSession.getSessionVisibleFromTime(),
                oldSession.getResultsVisibleFromTime(),
                Duration.ofMinutes(oldSession.getGracePeriod()),
                oldSession.isOpeningEmailEnabled(),
                oldSession.isClosingEmailEnabled(),
                oldSession.isPublishedEmailEnabled());

        newSession.setClosedEmailSent(oldSession.isSentClosedEmail());
        newSession.setClosingSoonEmailSent(oldSession.isSentClosingEmail());
        newSession.setOpenEmailSent(oldSession.isSentOpenEmail());
        newSession.setOpeningSoonEmailSent(oldSession.isSentOpeningSoonEmail());
        newSession.setPublishedEmailSent(oldSession.isSentPublishedEmail());
        newSession.setCreatedAt(oldSession.getCreatedTime());
        newSession.setUpdatedAt(Instant.now()); // not present in datastore session
        newSession.setDeletedAt(oldSession.getDeletedTime());

        return newSession;
    }

    private teammates.storage.sqlentity.FeedbackQuestion createFeedbackQuestion(
            teammates.storage.sqlentity.FeedbackSession newSession, FeedbackQuestion oldQuestion) {

        teammates.storage.sqlentity.FeedbackQuestion newFeedbackQuestion = teammates.storage.sqlentity.FeedbackQuestion
                .makeQuestion(
                        newSession,
                        oldQuestion.getQuestionNumber(),
                        oldQuestion.getQuestionDescription(),
                        oldQuestion.getGiverType(),
                        oldQuestion.getRecipientType(),
                        oldQuestion.getNumberOfEntitiesToGiveFeedbackTo(),
                        oldQuestion.getShowResponsesTo(),
                        oldQuestion.getShowGiverNameTo(),
                        oldQuestion.getShowRecipientNameTo(),
                        getFeedbackQuestionDetails(oldQuestion));

        newFeedbackQuestion.setCreatedAt(oldQuestion.getCreatedAt());
        newFeedbackQuestion.setUpdatedAt(oldQuestion.getUpdatedAt());

        return newFeedbackQuestion;
    }

    private FeedbackQuestionDetails getFeedbackQuestionDetails(FeedbackQuestion oldQuestion) {
        switch (oldQuestion.getQuestionType()) {
        case MCQ:
            return new FeedbackMcqQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case MSQ:
            return new FeedbackMsqQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case TEXT:
            return new FeedbackTextQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case RUBRIC:
            return new FeedbackRubricQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case CONTRIB:
            return new FeedbackContributionQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case CONSTSUM:
        case CONSTSUM_RECIPIENTS:
        case CONSTSUM_OPTIONS:
            return new FeedbackConstantSumQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case NUMSCALE:
            return new FeedbackNumericalScaleQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case RANK_OPTIONS:
            return new FeedbackRankOptionsQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        case RANK_RECIPIENTS:
            return new FeedbackRankRecipientsQuestionDetailsConverter()
                    .convertToEntityAttribute(oldQuestion.getQuestionText());
        default:
            throw new IllegalArgumentException("Invalid question type");
        }
    }

    private teammates.storage.sqlentity.FeedbackResponse createFeedbackResponse(
            teammates.storage.sqlentity.FeedbackQuestion newQuestion, FeedbackResponse oldResponse,
            Section giverSection, Section recipientSection) {
        teammates.storage.sqlentity.FeedbackResponse newResponse = teammates.storage.sqlentity.FeedbackResponse
                .makeResponse(
                        newQuestion,
                        oldResponse.getGiverEmail(),
                        giverSection,
                        oldResponse.getRecipientEmail(),
                        recipientSection,
                        getFeedbackResponseDetails(oldResponse));

        newResponse.setCreatedAt(oldResponse.getCreatedAt());
        newResponse.setUpdatedAt(oldResponse.getUpdatedAt());

        return newResponse;
    }

    private FeedbackResponseDetails getFeedbackResponseDetails(FeedbackResponse oldResponse) {
        switch (oldResponse.getFeedbackQuestionType()) {
        case MCQ:
            return new FeedbackTextResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case MSQ:
            return new FeedbackMsqResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case TEXT:
            return new FeedbackTextResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case RUBRIC:
            return new FeedbackRubricResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case CONTRIB:
            return new FeedbackContributionResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case CONSTSUM:
        case CONSTSUM_RECIPIENTS:
        case CONSTSUM_OPTIONS:
            return new FeedbackConstantSumResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case NUMSCALE:
            return new FeedbackNumericalScaleResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case RANK_OPTIONS:
            return new FeedbackRankOptionsResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        case RANK_RECIPIENTS:
            return new FeedbackRankRecipientsResponseDetailsConverter()
                    .convertToEntityAttribute(oldResponse.getAnswer());
        default:
            throw new IllegalArgumentException("Invalid response type");
        }
    }

    private teammates.storage.sqlentity.FeedbackResponseComment createFeedbackResponseComment(
            teammates.storage.sqlentity.FeedbackResponse newResponse, FeedbackResponseComment oldComment,
            Section giverSection, Section recipientSection) {
        teammates.storage.sqlentity.FeedbackResponseComment newComment =
                new teammates.storage.sqlentity.FeedbackResponseComment(
                    newResponse,
                    oldComment.getGiverEmail(),
                    oldComment.getCommentGiverType(),
                    giverSection,
                    recipientSection,
                    oldComment.getCommentText(),
                    oldComment.getIsVisibilityFollowingFeedbackQuestion(),
                    oldComment.getIsCommentFromFeedbackParticipant(),
                    oldComment.getShowCommentTo(),
                    oldComment.getShowGiverNameTo(),
                    oldComment.getLastEditorEmail());

        newComment.setCreatedAt(oldComment.getCreatedAt());
        newComment.setUpdatedAt(oldComment.getLastEditedAt());

        return newComment;
    }

    // methods for misc migration methods ---------------------------------------------------------------------------------

    private void migrateInstructorEntities(teammates.storage.sqlentity.Course newCourse,
            Map<String, User> userEmailToUserMap) {
        List<Instructor> oldInstructors = ofy().load().type(Instructor.class).filter("courseId", newCourse.getId())
                .list();
        for (Instructor oldInstructor : oldInstructors) {
            teammates.storage.sqlentity.Instructor newInstructor = migrateInstructor(newCourse, oldInstructor);
            userEmailToUserMap.put(oldInstructor.getEmail(), newInstructor);
        }
    }

    private teammates.storage.sqlentity.Instructor migrateInstructor(teammates.storage.sqlentity.Course newCourse,
            Instructor oldInstructor) {
        InstructorPrivileges newPrivileges;
        if (oldInstructor.getInstructorPrivilegesAsText() == null) {
            newPrivileges = new InstructorPrivileges(oldInstructor.getRole());
        } else {
            InstructorPrivilegesLegacy privilegesLegacy = JsonUtils
                    .fromJson(oldInstructor.getInstructorPrivilegesAsText(), InstructorPrivilegesLegacy.class);
            newPrivileges = new InstructorPrivileges(privilegesLegacy);
        }

        teammates.storage.sqlentity.Instructor newInstructor = new teammates.storage.sqlentity.Instructor(
                newCourse,
                oldInstructor.getName(),
                oldInstructor.getEmail(),
                oldInstructor.isDisplayedToStudents(),
                oldInstructor.getDisplayedName(),
                InstructorPermissionRole.getEnum(oldInstructor.getRole()),
                newPrivileges);

        newInstructor.setCreatedAt(oldInstructor.getCreatedAt());
        newInstructor.setUpdatedAt(oldInstructor.getUpdatedAt());
        newInstructor.setRegKey(oldInstructor.getRegistrationKey());

        saveEntityDeferred(newInstructor);
        return newInstructor;
    }

    private void migrateDeadlineExtensionEntities(teammates.storage.sqlentity.Course newCourse,
            Map<String, teammates.storage.sqlentity.FeedbackSession> feedbackSessionNameToFeedbackSessionMap,
            Map<String, User> userEmailToUserMap) {
        List<DeadlineExtension> oldDeadlineExtensions = ofy().load().type(DeadlineExtension.class)
                .filter("courseId", newCourse.getId())
                .list();

        for (DeadlineExtension oldDeadlineExtension : oldDeadlineExtensions) {
            User newUser = userEmailToUserMap.get(oldDeadlineExtension.getUserEmail());
            if (newUser == null) {
                continue;
            }
            migrateDeadlineExtension(oldDeadlineExtension,
                    feedbackSessionNameToFeedbackSessionMap.get(oldDeadlineExtension.getFeedbackSessionName()),
                    newUser);
        }
    }

    private void migrateDeadlineExtension(DeadlineExtension oldDeadlineExtension,
                                          teammates.storage.sqlentity.FeedbackSession feedbackSession,
                                          User newUser) {

        teammates.storage.sqlentity.DeadlineExtension newDeadlineExtension =
                new teammates.storage.sqlentity.DeadlineExtension(
                    newUser,
                    feedbackSession,
                    oldDeadlineExtension.getEndTime());

        newDeadlineExtension.setCreatedAt(oldDeadlineExtension.getCreatedAt());
        newDeadlineExtension.setUpdatedAt(oldDeadlineExtension.getUpdatedAt());
        newDeadlineExtension.setClosingSoonEmailSent(oldDeadlineExtension.getSentClosingEmail());

        saveEntityDeferred(newDeadlineExtension);
    }

}
