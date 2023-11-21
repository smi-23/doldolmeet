package com.doldolmeet.domain.openvidu.service;

import com.doldolmeet.domain.commons.Role;
import com.doldolmeet.domain.fanMeeting.entity.FanMeeting;
import com.doldolmeet.domain.teleRoom.entity.TeleRoom;
import com.doldolmeet.domain.fanMeeting.repository.FanMeetingRepository;
import com.doldolmeet.domain.teleRoom.repository.TeleRoomRepository;
import com.doldolmeet.domain.users.fan.entity.Fan;
import com.doldolmeet.domain.users.fan.repository.FanRepository;
import com.doldolmeet.domain.users.idol.entity.Idol;
import com.doldolmeet.domain.waitRoom.entity.WaitRoom;
import com.doldolmeet.domain.waitRoom.entity.WaitRoomFan;
import com.doldolmeet.domain.waitRoom.repository.WaitRoomFanRepository;
import com.doldolmeet.domain.waitRoom.repository.WaitRoomRepository;
import com.doldolmeet.exception.CustomException;
import com.doldolmeet.security.jwt.JwtUtil;
import com.doldolmeet.utils.Message;
import com.doldolmeet.utils.UserUtils;
import io.jsonwebtoken.Claims;
import io.openvidu.java.client.*;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.doldolmeet.exception.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class OpenviduService {
    private final JwtUtil jwtUtil;
    private final UserUtils userUtils;
    private final FanRepository fanRepository;
    private final FanMeetingRepository fanMeetingRepository;
    private final WaitRoomRepository waitRoomRepository;
    private final TeleRoomRepository teleRoomRepository;
    private final WaitRoomFanRepository waitRoomFanRepository;

    private Claims claims;
    @Value("${OPENVIDU_URL}")
    private String OPENVIDU_URL;

    @Value("${OPENVIDU_SECRET}")
    private String OPENVIDU_SECRET;

    private OpenVidu openvidu;


    @PostConstruct
    public void init() {
        this.openvidu = new OpenVidu(OPENVIDU_URL, OPENVIDU_SECRET);
    }

    public ResponseEntity<String> initializeSession(Map<String, Object> params) throws OpenViduJavaClientException, OpenViduHttpException {
        SessionProperties properties = SessionProperties.fromJson(params).build();
        Session session = openvidu.createSession(properties);
        return new ResponseEntity<>(session.getSessionId(), HttpStatus.OK);
    }

    public ResponseEntity<String> createConnection(String sessionId, Map<String, Object> params) throws OpenViduJavaClientException, OpenViduHttpException{
        Session session = openvidu.getActiveSession(sessionId);
        if (session == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        ConnectionProperties properties = ConnectionProperties.fromJson(params).build();
        Connection connection = session.createConnection(properties);
        return new ResponseEntity<>(connection.getToken(), HttpStatus.OK);
    }

    @Transactional
    public ResponseEntity<Message> enterFanMeeting(Long fanMeetingId, HttpServletRequest request) throws OpenViduJavaClientException, OpenViduHttpException {
        claims = jwtUtil.getClaims(request);
        String role = (String)claims.get("auth");

        Optional<FanMeeting> fanMeeting = fanMeetingRepository.findById(fanMeetingId);

        // 존재하지 않는 팬미팅.
        if (!fanMeeting.isPresent()) {
            throw new CustomException(FANMEETING_NOT_FOUND);
        }

        // 팬인 경우 재접속 판단.
        if (role.equals(Role.FAN.getKey())) {
            Fan fan = userUtils.getFan(claims.getSubject());
            WaitRoom waitRoom;
            Session waitRoomSession;

            // 일단 재접속 유무 검사.
            Optional<WaitRoomFan> waitRoomFan = waitRoomFanRepository.findByFanIdAndWaitRoomId(fan.getId(), fanMeetingId);

            // 재접속이면,
            if (waitRoomFan.isPresent()) {
                Long idx = waitRoomFan.get().getNextWaitRoomIdx();
                waitRoom = fanMeeting.get().getWaitRooms().get(idx.intValue());

                // 다시 연결
                waitRoom.getWaitRoomFans().add(waitRoomFan.get()); // waitRoom에 팬 추가.
                Session session = openvidu.getActiveSession(waitRoom.getRoomId());

                if (session == null) {
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }

                ConnectionProperties properties = ConnectionProperties.fromJson(new HashMap<>()).build();
                Connection connection = session.createConnection(properties);
                waitRoomFan.get().setConnectionId(connection.getConnectionId());

                return new ResponseEntity<>(new Message("팬 재접속 성공", connection.getToken()), HttpStatus.OK);
            }

            // 존재 안하면,
            else {
                // 새로 만들기
                WaitRoomFan newWaitRoomFan = WaitRoomFan.builder()
                        .nextTeleRoomIdx(0L)
                        .nextWaitRoomIdx(0L)
                        .fan(userUtils.getFan(claims.getSubject()))
                        .build();

                waitRoom = fanMeeting.get().getWaitRooms().get(0); // TODO: 0 대신 nextWaitRoomIdx 넣기
                newWaitRoomFan.setWaitRoom(waitRoom);
                waitRoomSession = openvidu.getActiveSession(fanMeeting.get().getWaitRooms().get(0).getRoomId());
                waitRoom.getWaitRoomFans().add(newWaitRoomFan);
                ConnectionProperties properties = ConnectionProperties.fromJson(new HashMap<>()).build(); // params에 뭐?
                Connection connection = waitRoomSession.createConnection(properties);
                newWaitRoomFan.setConnectionId(connection.getConnectionId());

                if (waitRoomSession == null) {
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }

                waitRoomFanRepository.save(newWaitRoomFan);
                return new ResponseEntity<>(new Message("팬미팅 입장 성공", connection.getToken()), HttpStatus.OK);
            }
        }

        // 아이돌이 입장버튼 누를시, 자기방 생성
        else if (role.equals(Role.IDOL.getKey())) {
            Map<String, String> result = new HashMap<>();
            Idol idol = userUtils.getIdol(claims.getSubject());

            // 아직 첫번째 대기방 세션 생성 안되어있으면 생성하기
            if (fanMeeting.get().getIsFirstWaitRoomCreated()) {
                WaitRoom waitRoom = fanMeeting.get().getWaitRooms().get(0);
                Map<String, Object> param = new HashMap<>();
                param.put("customSessionId", waitRoom.getRoomId());
                SessionProperties properties = SessionProperties.fromJson(param).build();

                try {
                    Session session = openvidu.createSession(properties);
                    fanMeeting.get().setIsFirstWaitRoomCreated(true);
                    result.put("mainWaitRoomId", session.getSessionId());
                    waitRoomRepository.save(waitRoom);
                } catch (OpenViduHttpException e) {
                    if (e.getStatus() == 409) {
                        return new ResponseEntity<>(new Message("이미 존재하는 방입니다.", null), HttpStatus.CONFLICT);
                    } else {
                        return new ResponseEntity<>(new Message("openvidu 오류", null), HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                }
            }
            Map<String, Object> param1 = new HashMap<>(); // videoRoom용
            Map<String, Object> param2 = new HashMap<>(); // waitList용

            param1.put("customSessionId", idol.getTeleRoomId());
            param2.put("customSessionId", idol.getWaitRoomId());

            SessionProperties properties1 = SessionProperties.fromJson(param1).build();
            SessionProperties properties2 = SessionProperties.fromJson(param2).build();

            try {
                Session teleSession = openvidu.createSession(properties1);
                Session waitSession = openvidu.createSession(properties2);

                result.put("teleRoomId", teleSession.getSessionId());
                result.put("waitRoomId", waitSession.getSessionId());

                WaitRoom waitRoom = new WaitRoom();
                waitRoom.setRoomId(idol.getWaitRoomId());

                TeleRoom teleRoom = new TeleRoom();
                teleRoom.setRoomId(idol.getTeleRoomId());

                fanMeeting.get().getWaitRooms().add(waitRoom);
                fanMeeting.get().getTeleRooms().add(teleRoom);

                waitRoomRepository.save(waitRoom);
                teleRoomRepository.save(teleRoom);

                Session session = openvidu.getActiveSession(teleSession.getSessionId());

                if (session == null) {
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }

                ConnectionProperties properties = ConnectionProperties.fromJson(new HashMap<>()).build(); // params에 뭐?
                Connection connection = session.createConnection(properties);
                result.put("token", connection.getToken());

                return new ResponseEntity<>(new Message("아이돌 방생성 및 입장 성공", result), HttpStatus.OK);
            } catch (OpenViduHttpException e) {
                if (e.getStatus() == 409) {
                    return new ResponseEntity<>(new Message("이미 존재하는 방입니다.", null), HttpStatus.CONFLICT);
                } else {
                    return new ResponseEntity<>(new Message("openvidu 오류", null), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
        }

        else {
            throw new CustomException(USER_NOT_FOUND);
        }
    }
}
