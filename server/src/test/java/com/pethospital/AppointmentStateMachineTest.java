package com.pethospital;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static com.pethospital.appointment.AppointmentStateMachine.*;
import com.pethospital.appointment.AppointmentStateMachine;
import com.pethospital.shared.BusinessException;
class AppointmentStateMachineTest {
 final AppointmentStateMachine policy=new AppointmentStateMachine();
 final Instant start=Instant.parse("2026-10-29T01:00:00Z"),now=start.minusSeconds(60),deadline=start;
 @Test void normalFlowAndManualNoShow(){
  assertThat(policy.transition(Status.PENDING,Action.CONFIRM,Actor.DOCTOR,now,start,deadline)).isEqualTo(Status.CONFIRMED);
  assertThat(policy.transition(Status.CONFIRMED,Action.START,Actor.DOCTOR,now,start,deadline)).isEqualTo(Status.IN_CONSULTATION);
  assertThat(policy.transition(Status.IN_CONSULTATION,Action.COMPLETE,Actor.DOCTOR,now,start,deadline)).isEqualTo(Status.COMPLETED);
  assertThat(policy.transition(Status.CONFIRMED,Action.MARK_NO_SHOW,Actor.DOCTOR,start.plusSeconds(1800),start,deadline)).isEqualTo(Status.NO_SHOW);
 }
 @ParameterizedTest @EnumSource(Status.class) void terminalsNeverReopen(Status state){
  if(state==Status.COMPLETED||state==Status.CANCELLED||state==Status.REJECTED||state==Status.NO_SHOW){
   for(Action a:Action.values())assertThatThrownBy(()->policy.transition(state,a,Actor.DOCTOR,now,start,deadline)).isInstanceOf(BusinessException.class);
  }
 }
 @Test void adminsCannotPerformDoctorActions(){
  for(Action a:Action.values())if(a!=Action.CANCEL)assertThatThrownBy(()->policy.transition(Status.CONFIRMED,a,Actor.ADMIN,now,start,deadline)).isInstanceOf(BusinessException.class);
 }
 @Test void temporalBoundaries(){
  assertThatThrownBy(()->policy.transition(Status.PENDING,Action.CONFIRM,Actor.DOCTOR,deadline,start,deadline)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->policy.transition(Status.CONFIRMED,Action.START,Actor.DOCTOR,start.minusSeconds(901),start,deadline)).isInstanceOf(BusinessException.class);
  assertThat(policy.transition(Status.CONFIRMED,Action.START,Actor.DOCTOR,start.minusSeconds(900),start,deadline)).isEqualTo(Status.IN_CONSULTATION);
  assertThatThrownBy(()->policy.transition(Status.CONFIRMED,Action.MARK_NO_SHOW,Actor.DOCTOR,start.plusSeconds(1799),start,deadline)).isInstanceOf(BusinessException.class);
 }
 @Test void cancellationSources(){
  assertThatThrownBy(()->policy.transition(Status.CONFIRMED,Action.CANCEL,Actor.USER,start,start,deadline)).isInstanceOf(BusinessException.class);
  assertThat(policy.transition(Status.CONFIRMED,Action.CANCEL,Actor.ADMIN,start,start,deadline)).isEqualTo(Status.CANCELLED);
  assertThat(policy.transition(Status.PENDING,Action.CANCEL,Actor.SYSTEM,start,start,deadline)).isEqualTo(Status.CANCELLED);
  assertThatThrownBy(()->policy.transition(Status.CONFIRMED,Action.CANCEL,Actor.SYSTEM,start,start,deadline)).isInstanceOf(BusinessException.class);
 }
 @Test void deadlinesNeverExtendOnReschedule(){
  assertThat(policy.confirmationDeadline(now,start)).isEqualTo(start);
  assertThat(policy.confirmationDeadline(start.minusSeconds(90000),start)).isEqualTo(start.minusSeconds(3600));
  assertThat(policy.rescheduleDeadline(deadline,start.plusSeconds(3600))).isEqualTo(deadline);
  assertThat(policy.rescheduleDeadline(deadline,start.minusSeconds(3600))).isEqualTo(start.minusSeconds(3600));
 }
}
