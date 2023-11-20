package com.doldolmeet.domain.fanMeeting.service;

import com.doldolmeet.domain.fanMeeting.dto.request.FanMeetingRequestDto;
import com.doldolmeet.domain.fanMeeting.dto.response.FanMeetingResponseDto;
import com.doldolmeet.domain.fanMeeting.dto.response.FanToFanMeetingResponseDto;
import com.doldolmeet.domain.fanMeeting.entity.FanMeeting;
import com.doldolmeet.domain.fanMeeting.entity.FanMeetingApplyStatus;
import com.doldolmeet.domain.fanMeeting.entity.FanMeetingSearchOption;
import com.doldolmeet.domain.fanMeeting.entity.FanToFanMeeting;
import com.doldolmeet.domain.fanMeeting.repository.FanMeetingRepository;
import com.doldolmeet.domain.fanMeeting.repository.FanToFanMeetingRepository;
import com.doldolmeet.domain.team.entity.Team;
import com.doldolmeet.domain.team.repository.TeamRepository;
import com.doldolmeet.domain.users.admin.entity.Admin;
import com.doldolmeet.domain.users.fan.entity.Fan;
import com.doldolmeet.exception.CustomException;
import com.doldolmeet.exception.ErrorCode;
import com.doldolmeet.security.jwt.JwtUtil;
import com.doldolmeet.utils.Message;
import com.doldolmeet.utils.UserUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.doldolmeet.exception.ErrorCode.*;

@Service
@RequiredArgsConstructor
public class FanMeetingService {
    private final FanMeetingRepository fanMeetingRepository;
    private final TeamRepository teamRepository;
    private final FanToFanMeetingRepository fanToFanMeetingRepository;
    private final JwtUtil jwtUtil;
    private final UserUtils userUtils;
    private Claims claims;

    public ResponseEntity<Message> createFanMeeting(FanMeetingRequestDto requestDto, HttpServletRequest request) {
        claims = jwtUtil.getClaims(request);
        userUtils.checkIfAdmin(claims);

        Optional<Team> team = teamRepository.findByTeamName(requestDto.getTeamName());

        if (!team.isPresent()) {
            throw new CustomException(ErrorCode.TEAM_NOT_FOUND);
        }

        FanMeeting fanMeeting = FanMeeting.builder()
                .startTime(requestDto.getStartTime())
                .endTime(requestDto.getEndTime())
                .capacity(requestDto.getCapacity())
                .fanMeetingName(requestDto.getFanMeetingName())
                .team(team.get())
                .roomId(UUID.randomUUID().toString())
                .build();

        fanMeetingRepository.save(fanMeeting);
        return new ResponseEntity<>(new Message("팬미팅 생성 완료", null), HttpStatus.OK);
    }

    public ResponseEntity<Message> getFanMeetings(String option) {
        List<FanMeetingResponseDto> result = new ArrayList<>();
        List<FanMeeting> fanMeetings;

        if (option.equals(FanMeetingSearchOption.OPENED.value())) {
            fanMeetings = fanMeetingRepository.findFanMeetingsByEndTimeAfter(LocalDateTime.now());
        }
        else if (option.equals(FanMeetingSearchOption.CLOSED.value())) {
            fanMeetings = fanMeetingRepository.findFanMeetingsByEndTimeBefore(LocalDateTime.now());
        }

        else {
            fanMeetings = fanMeetingRepository.findAll();
        }

        for (FanMeeting fanMeeting : fanMeetings) {
            FanMeetingResponseDto responseDto = FanMeetingResponseDto.builder()
                    .id(fanMeeting.getId())
                    .imgUrl(fanMeeting.getFanMeetingImgUrl())
                    .title(fanMeeting.getFanMeetingName())
                    .startTime(fanMeeting.getStartTime())
                    .build();

            result.add(responseDto);
        }

        return new ResponseEntity<>(new Message("팬미팅 조회 성공", result), HttpStatus.OK);
    }

    public ResponseEntity<Message> applyFanMeeting(Long fanMeetingId, HttpServletRequest request) {
        claims = jwtUtil.getClaims(request);
        Fan fan = userUtils.getFan(claims.getSubject());

        Optional<FanMeeting> fanMeeting = fanMeetingRepository.findById(fanMeetingId);

        if (!fanMeeting.isPresent()) {
            throw new CustomException(FANMEETING_NOT_FOUND);
        }

        FanToFanMeeting fanToFanMeeting = FanToFanMeeting.builder()
                .fanMeetingApplyStatus(FanMeetingApplyStatus.APPROVED)
                .fan(fan)
                .fanMeeting(fanMeeting.get())
                .build();

        fan.getFanToFanMeetings().add(fanToFanMeeting);
        fanMeeting.get().getFanToFanMeetings().add(fanToFanMeeting);

        fanToFanMeetingRepository.save(fanToFanMeeting);

        FanToFanMeetingResponseDto responseDto = FanToFanMeetingResponseDto.builder()
                .id(fanToFanMeeting.getId())
                .fanMeetingId(fanMeetingId)
                .fanId(fan.getId())
                .fanMeetingApplyStatus(FanMeetingApplyStatus.APPROVED)
                .build();

        return new ResponseEntity<>(new Message("팬미팅 신청 성공", responseDto), HttpStatus.OK);
    }

    public ResponseEntity<Message> getMyLatestFanMeeting(HttpServletRequest request) {
        claims = jwtUtil.getClaims(request);
        Fan fan = userUtils.getFan(claims.getSubject());

        LocalDateTime currentTime = LocalDateTime.now();
        Optional<FanMeeting> fanMeetingOpt = fanMeetingRepository.findEarliestFanMeetingByFan(fan, currentTime);

        if (!fanMeetingOpt.isPresent()) {
            throw new CustomException(FANMEETING_NOT_FOUND);
        }

        FanMeeting fanMeeting = fanMeetingOpt.get();

        FanMeetingResponseDto responseDto = FanMeetingResponseDto.builder()
                .id(fanMeeting.getId())
                .imgUrl(fanMeeting.getFanMeetingImgUrl())
                .title(fanMeeting.getFanMeetingName())
                .startTime(fanMeeting.getStartTime())
                .build();

        return new ResponseEntity<>(new Message("나의 예정된 팬미팅 중 가장 최신 팬미팅 받기 성공", responseDto), HttpStatus.OK);
    }

    public ResponseEntity<Message> canEnterFanMeeting(Long fanMeetingId, HttpServletRequest request) {
        claims = jwtUtil.getClaims(request);
        Fan fan = userUtils.getFan(claims.getSubject());

        Optional<FanMeeting> fanMeetingOpt = fanMeetingRepository.findById(fanMeetingId);

        if (!fanMeetingOpt.isPresent()) {
            throw new CustomException(FANMEETING_NOT_FOUND);
        }

        FanMeeting fanMeeting = fanMeetingOpt.get();

        Optional<FanToFanMeeting> fanToFanMeetingOpt = fanToFanMeetingRepository.findByFanAndFanMeeting(fan, fanMeeting);

        if (!fanToFanMeetingOpt.isPresent()) {
            throw new CustomException(FANMEETING_NOT_FOUND);
        }

        FanToFanMeeting fanToFanMeeting = fanToFanMeetingOpt.get();

        if (!fanToFanMeeting.getFanMeetingApplyStatus().equals(FanMeetingApplyStatus.APPROVED)) {
            throw new CustomException(FANMEETING_NOT_FOUND);
        }

        return new ResponseEntity<>(new Message("팬미팅 입장 가능", null), HttpStatus.OK);
    }
}