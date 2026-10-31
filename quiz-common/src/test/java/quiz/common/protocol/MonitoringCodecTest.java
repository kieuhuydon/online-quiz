package quiz.common.protocol;

import org.junit.jupiter.api.Test;
import quiz.common.dto.*;
import quiz.common.enums.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringCodecTest {
    @Test void monitoringPayloadsRoundTripThroughAllowlist() throws Exception {
        List<java.io.Serializable> payloads=List.of(
            new ExamSession(1,2,3,"Sinh viên","127.0.0.1",MonitoringStatus.CHEATING,1,"Discord",2,300,false,Instant.now(),60),
            new ApplyPenaltyDto(UUID.randomUUID().toString(),1,PenaltyType.DEDUCT_SCORE,2,"Discord"),
            new ArrayList<>(List.of(new MonitoringPenalty(4,PenaltyType.TERMINATE,0,"Reason",Instant.now()))));
        for(var payload:payloads) {
            Response input=Response.success(1,payload);
            Response output=(Response)MessageCodec.deserialize(MessageCodec.serialize(input));
            assertEquals(payload,output.payload());
        }
    }
}
