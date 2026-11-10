package com.pethospital.appointment;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import com.pethospital.shared.BusinessException;
import org.springframework.http.HttpStatus;

/** Pure transition policy; object ownership must additionally be checked by the transactional service. */
public final class AppointmentStateMachine {
 public enum Status { PENDING, CONFIRMED, IN_CONSULTATION, COMPLETED, REJECTED, CANCELLED, NO_SHOW }
 public enum Action { CONFIRM, REJECT, CANCEL, START, COMPLETE, MARK_NO_SHOW }
 public enum Actor { USER, DOCTOR, ADMIN, SYSTEM }
 private final Duration earlyStart;
 private final Duration noShowGrace;
 public AppointmentStateMachine(){ this(Duration.ofMinutes(15),Duration.ofMinutes(30)); }
 public AppointmentStateMachine(Duration earlyStart,Duration noShowGrace){
  if(earlyStart.isNegative()||noShowGrace.isNegative())throw new IllegalArgumentException("Negative window");
  this.earlyStart=earlyStart;this.noShowGrace=noShowGrace;
 }
 public Status transition(Status state,Action action,Actor actor,Instant now,Instant startsAt,Instant deadline){
  Objects.requireNonNull(state);Objects.requireNonNull(action);Objects.requireNonNull(actor);
  Objects.requireNonNull(now);Objects.requireNonNull(startsAt);Objects.requireNonNull(deadline);
  if(action!=Action.CANCEL && actor!=Actor.DOCTOR)throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","该操作仅限接诊医生");
  return switch(action){
   case CONFIRM -> { require(state==Status.PENDING && now.isBefore(deadline) && now.isBefore(startsAt)); yield Status.CONFIRMED; }
   case REJECT -> { require(state==Status.PENDING); yield Status.REJECTED; }
   case START -> { require(state==Status.CONFIRMED && !now.isBefore(startsAt.minus(earlyStart)));yield Status.IN_CONSULTATION; }
   case COMPLETE -> { require(state==Status.IN_CONSULTATION);yield Status.COMPLETED; }
   case MARK_NO_SHOW -> { require(state==Status.CONFIRMED && !now.isBefore(startsAt.plus(noShowGrace)));yield Status.NO_SHOW; }
   case CANCEL -> {
    require(state==Status.PENDING||state==Status.CONFIRMED);
    if(actor==Actor.USER)require(now.isBefore(startsAt));
    if(actor==Actor.SYSTEM)require(state==Status.PENDING && !now.isBefore(deadline));
    yield Status.CANCELLED;
   }
  };
 }
 public Instant confirmationDeadline(Instant createdAt,Instant startsAt){
  if(!startsAt.isAfter(createdAt))throw new IllegalArgumentException("Slot must be in the future");
  return earlier(createdAt.plus(Duration.ofHours(24)),startsAt);
 }
 public Instant rescheduleDeadline(Instant originalDeadline,Instant newStart){ return earlier(originalDeadline,newStart); }
 private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
 private static void require(boolean allowed){if(!allowed)throw new BusinessException(HttpStatus.CONFLICT,"STATE_CONFLICT","预约状态或操作时间不允许");}
}
