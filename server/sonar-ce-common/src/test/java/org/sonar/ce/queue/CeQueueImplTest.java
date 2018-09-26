/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.queue;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.TestSystem2;
import org.sonar.ce.queue.CeTaskSubmit.Component;
import org.sonar.ce.task.CeTask;
import org.sonar.core.util.UuidFactory;
import org.sonar.core.util.UuidFactoryFast;
import org.sonar.core.util.UuidFactoryImpl;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.ce.CeActivityDto;
import org.sonar.db.ce.CeQueueDto;
import org.sonar.db.ce.CeTaskTypes;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.organization.TestDefaultOrganizationProvider;

import static com.google.common.collect.ImmutableList.of;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.startsWith;
import static org.sonar.ce.queue.CeQueue.SubmitOption.UNIQUE_QUEUE_PER_MAIN_COMPONENT;

public class CeQueueImplTest {

  private static final String WORKER_UUID = "workerUuid";

  private System2 system2 = new TestSystem2().setNow(1_450_000_000_000L);

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public DbTester db = DbTester.create(system2);

  private DbSession session = db.getSession();

  private UuidFactory uuidFactory = UuidFactoryImpl.INSTANCE;
  private DefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);

  private CeQueue underTest = new CeQueueImpl(db.getDbClient(), uuidFactory, defaultOrganizationProvider);

  @Test
  public void submit_returns_task_populated_from_CeTaskSubmit_and_creates_CeQueue_row() {
    String componentUuid = randomAlphabetic(3);
    String mainComponentUuid = randomAlphabetic(4);
    CeTaskSubmit taskSubmit = createTaskSubmit(CeTaskTypes.REPORT, new Component(componentUuid, mainComponentUuid), "submitter uuid");

    CeTask task = underTest.submit(taskSubmit);

    verifyCeTask(taskSubmit, task, null);
    verifyCeQueueDtoForTaskSubmit(taskSubmit);
  }

  @Test
  public void submit_populates_component_name_and_key_of_CeTask_if_component_exists() {
    ComponentDto componentDto = insertComponent(ComponentTesting.newPrivateProjectDto(db.organizations().insert(), "PROJECT_1"));
    CeTaskSubmit taskSubmit = createTaskSubmit(CeTaskTypes.REPORT, Component.fromDto(componentDto), null);

    CeTask task = underTest.submit(taskSubmit);

    verifyCeTask(taskSubmit, task, componentDto);
  }

  @Test
  public void submit_returns_task_without_component_info_when_submit_has_none() {
    CeTaskSubmit taskSubmit = createTaskSubmit("not cpt related");

    CeTask task = underTest.submit(taskSubmit);

    verifyCeTask(taskSubmit, task, null);
  }

  @Test
  public void submit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_without_component_when_there_is_a_pending_task_without_component() {
    CeTaskSubmit taskSubmit = createTaskSubmit("no_component");
    CeQueueDto dto = insertPendingInQueue(null);

    Optional<CeTask> task = underTest.submit(taskSubmit, UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(task).isNotEmpty();
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid(), task.get().getUuid());
  }

  @Test
  public void submit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_when_there_is_a_pending_task_for_another_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    String otherMainComponentUuid = randomAlphabetic(6);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    CeQueueDto dto = insertPendingInQueue(newComponent(otherMainComponentUuid));

    Optional<CeTask> task = underTest.submit(taskSubmit, UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(task).isNotEmpty();
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid(), task.get().getUuid());
  }

  @Test
  public void submit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_does_not_create_task_when_there_is_one_pending_task_for_same_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    CeQueueDto dto = insertPendingInQueue(newComponent(mainComponentUuid));

    Optional<CeTask> task = underTest.submit(taskSubmit, UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(task).isEmpty();
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid());
  }

  @Test
  public void submit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_does_not_create_task_when_there_is_many_pending_task_for_same_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    String[] uuids = IntStream.range(0, 2 + new Random().nextInt(5))
      .mapToObj(i -> insertPendingInQueue(newComponent(mainComponentUuid)))
      .map(CeQueueDto::getUuid)
      .toArray(String[]::new);

    Optional<CeTask> task = underTest.submit(taskSubmit, UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(task).isEmpty();
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(uuids);
  }

  @Test
  public void submit_without_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_when_there_is_one_pending_task_for_same_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    CeQueueDto dto = insertPendingInQueue(newComponent(mainComponentUuid));

    CeTask task = underTest.submit(taskSubmit);

    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid(), task.getUuid());
  }

  @Test
  public void submit_without_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_when_there_is_many_pending_task_for_same_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    String[] uuids = IntStream.range(0, 2 + new Random().nextInt(5))
      .mapToObj(i -> insertPendingInQueue(newComponent(mainComponentUuid)))
      .map(CeQueueDto::getUuid)
      .toArray(String[]::new);

    CeTask task = underTest.submit(taskSubmit);

    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .hasSize(uuids.length + 1)
      .contains(uuids)
      .contains(task.getUuid());
  }

  @Test
  public void massSubmit_returns_tasks_for_each_CeTaskSubmit_populated_from_CeTaskSubmit_and_creates_CeQueue_row_for_each() {
    String mainComponentUuid = randomAlphabetic(10);
    CeTaskSubmit taskSubmit1 = createTaskSubmit(CeTaskTypes.REPORT, newComponent(mainComponentUuid), "submitter uuid");
    CeTaskSubmit taskSubmit2 = createTaskSubmit("some type");

    List<CeTask> tasks = underTest.massSubmit(asList(taskSubmit1, taskSubmit2));

    assertThat(tasks).hasSize(2);
    verifyCeTask(taskSubmit1, tasks.get(0), null);
    verifyCeTask(taskSubmit2, tasks.get(1), null);
    verifyCeQueueDtoForTaskSubmit(taskSubmit1);
    verifyCeQueueDtoForTaskSubmit(taskSubmit2);
  }

  @Test
  public void massSubmit_populates_component_name_and_key_of_CeTask_if_project_exists() {
    ComponentDto componentDto1 = insertComponent(ComponentTesting.newPrivateProjectDto(db.getDefaultOrganization(), "PROJECT_1"));
    CeTaskSubmit taskSubmit1 = createTaskSubmit(CeTaskTypes.REPORT, Component.fromDto(componentDto1), null);
    CeTaskSubmit taskSubmit2 = createTaskSubmit("something", newComponent(randomAlphabetic(12)), null);

    List<CeTask> tasks = underTest.massSubmit(asList(taskSubmit1, taskSubmit2));

    assertThat(tasks).hasSize(2);
    verifyCeTask(taskSubmit1, tasks.get(0), componentDto1);
    verifyCeTask(taskSubmit2, tasks.get(1), null);
  }

  @Test
  public void massSubmit_populates_component_name_and_key_of_CeTask_if_project_and_branch_exists() {
    ComponentDto project = insertComponent(ComponentTesting.newPrivateProjectDto(db.getDefaultOrganization(), "PROJECT_1"));
    ComponentDto branch1 = db.components().insertProjectBranch(project);
    ComponentDto branch2 = db.components().insertProjectBranch(project);
    CeTaskSubmit taskSubmit1 = createTaskSubmit(CeTaskTypes.REPORT, Component.fromDto(branch1), null);
    CeTaskSubmit taskSubmit2 = createTaskSubmit("something", Component.fromDto(branch2), null);

    List<CeTask> tasks = underTest.massSubmit(asList(taskSubmit1, taskSubmit2));

    assertThat(tasks).hasSize(2);
    verifyCeTask(taskSubmit1, tasks.get(0), branch1, project);
    verifyCeTask(taskSubmit2, tasks.get(1), branch2, project);
  }

  @Test
  public void massSubmit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_without_component_when_there_is_a_pending_task_without_component() {
    CeTaskSubmit taskSubmit = createTaskSubmit("no_component");
    CeQueueDto dto = insertPendingInQueue(null);

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit), UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(tasks).hasSize(1);
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid(), tasks.iterator().next().getUuid());
  }

  @Test
  public void massSubmit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_when_there_is_a_pending_task_for_another_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    String otherMainComponentUuid = randomAlphabetic(6);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    CeQueueDto dto = insertPendingInQueue(newComponent(otherMainComponentUuid));

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit), UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(tasks).hasSize(1);
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid(), tasks.iterator().next().getUuid());
  }

  @Test
  public void massSubmit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_does_not_create_task_when_there_is_one_pending_task_for_same_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    CeQueueDto dto = insertPendingInQueue(newComponent(mainComponentUuid));

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit), UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(tasks).isEmpty();
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid());
  }

  @Test
  public void massSubmit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_does_not_create_task_when_there_is_many_pending_task_for_same_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    String[] uuids = IntStream.range(0, 2 + new Random().nextInt(5))
      .mapToObj(i -> insertPendingInQueue(newComponent(mainComponentUuid)))
      .map(CeQueueDto::getUuid)
      .toArray(String[]::new);

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit), UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(tasks).isEmpty();
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(uuids);
  }

  @Test
  public void massSubmit_without_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_when_there_is_one_pending_task_for_other_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    CeQueueDto dto = insertPendingInQueue(newComponent(mainComponentUuid));

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit));

    assertThat(tasks).hasSize(1);
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .containsOnly(dto.getUuid(), tasks.iterator().next().getUuid());
  }

  @Test
  public void massSubmit_without_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_task_when_there_is_many_pending_task_for_other_main_component() {
    String mainComponentUuid = randomAlphabetic(5);
    CeTaskSubmit taskSubmit = createTaskSubmit("with_component", newComponent(mainComponentUuid), null);
    String[] uuids = IntStream.range(0, 2 + new Random().nextInt(5))
      .mapToObj(i -> insertPendingInQueue(newComponent(mainComponentUuid)))
      .map(CeQueueDto::getUuid)
      .toArray(String[]::new);

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit));

    assertThat(tasks).hasSize(1);
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .hasSize(uuids.length + 1)
      .contains(uuids)
      .contains(tasks.iterator().next().getUuid());
  }

  @Test
  public void massSubmit_with_UNIQUE_QUEUE_PER_MAIN_COMPONENT_creates_tasks_depending_on_whether_there_is_pending_task_for_same_main_component() {
    String mainComponentUuid1 = randomAlphabetic(5);
    String mainComponentUuid2 = randomAlphabetic(6);
    String mainComponentUuid3 = randomAlphabetic(7);
    String mainComponentUuid4 = randomAlphabetic(8);
    String mainComponentUuid5 = randomAlphabetic(9);
    CeTaskSubmit taskSubmit1 = createTaskSubmit("with_one_pending", newComponent(mainComponentUuid1), null);
    CeQueueDto dto1 = insertPendingInQueue(newComponent(mainComponentUuid1));
    Component componentForMainComponentUuid2 = newComponent(mainComponentUuid2);
    CeTaskSubmit taskSubmit2 = createTaskSubmit("no_pending", componentForMainComponentUuid2, null);
    CeTaskSubmit taskSubmit3 = createTaskSubmit("with_many_pending", newComponent(mainComponentUuid3), null);
    String[] uuids3 = IntStream.range(0, 2 + new Random().nextInt(5))
      .mapToObj(i -> insertPendingInQueue(newComponent(mainComponentUuid3)))
      .map(CeQueueDto::getUuid)
      .toArray(String[]::new);
    Component componentForMainComponentUuid4 = newComponent(mainComponentUuid4);
    CeTaskSubmit taskSubmit4 = createTaskSubmit("no_pending_2", componentForMainComponentUuid4, null);
    CeTaskSubmit taskSubmit5 = createTaskSubmit("with_pending_2", newComponent(mainComponentUuid5), null);
    CeQueueDto dto5 = insertPendingInQueue(newComponent(mainComponentUuid5));

    List<CeTask> tasks = underTest.massSubmit(of(taskSubmit1, taskSubmit2, taskSubmit3, taskSubmit4, taskSubmit5), UNIQUE_QUEUE_PER_MAIN_COMPONENT);

    assertThat(tasks)
      .hasSize(2)
      .extracting(task -> task.getComponent().get().getUuid(),task -> task.getMainComponent().get().getUuid())
      .containsOnly(tuple(componentForMainComponentUuid2.getUuid(), componentForMainComponentUuid2.getMainComponentUuid()),
        tuple(componentForMainComponentUuid4.getUuid(), componentForMainComponentUuid4.getMainComponentUuid()));
    assertThat(db.getDbClient().ceQueueDao().selectAllInAscOrder(db.getSession()))
      .extracting(CeQueueDto::getUuid)
      .hasSize(1 + uuids3.length + 1 + tasks.size())
      .contains(dto1.getUuid())
      .contains(uuids3)
      .contains(dto5.getUuid())
      .containsAll(tasks.stream().map(CeTask::getUuid).collect(Collectors.toList()));
  }

  @Test
  public void cancel_pending() {
    CeTask task = submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(12)));
    CeQueueDto queueDto = db.getDbClient().ceQueueDao().selectByUuid(db.getSession(), task.getUuid()).get();

    underTest.cancel(db.getSession(), queueDto);

    Optional<CeActivityDto> activity = db.getDbClient().ceActivityDao().selectByUuid(db.getSession(), task.getUuid());
    assertThat(activity.isPresent()).isTrue();
    assertThat(activity.get().getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
  }

  @Test
  public void fail_to_cancel_if_in_progress() {
    submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(11)));
    CeQueueDto ceQueueDto = db.getDbClient().ceQueueDao().peek(session, WORKER_UUID).get();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage(startsWith("Task is in progress and can't be canceled"));

    underTest.cancel(db.getSession(), ceQueueDto);
  }

  @Test
  public void cancelAll_pendings_but_not_in_progress() {
    CeTask inProgressTask = submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(12)));
    CeTask pendingTask1 = submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(13)));
    CeTask pendingTask2 = submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(14)));

    db.getDbClient().ceQueueDao().peek(session, WORKER_UUID);

    int canceledCount = underTest.cancelAll();
    assertThat(canceledCount).isEqualTo(2);

    Optional<CeActivityDto> history = db.getDbClient().ceActivityDao().selectByUuid(db.getSession(), pendingTask1.getUuid());
    assertThat(history.get().getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
    history = db.getDbClient().ceActivityDao().selectByUuid(db.getSession(), pendingTask2.getUuid());
    assertThat(history.get().getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
    history = db.getDbClient().ceActivityDao().selectByUuid(db.getSession(), inProgressTask.getUuid());
    assertThat(history.isPresent()).isFalse();
  }

  @Test
  public void pauseWorkers_marks_workers_as_paused_if_zero_tasks_in_progress() {
    submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(12)));
    // task is pending

    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.RESUMED);

    underTest.pauseWorkers();
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.PAUSED);
  }

  @Test
  public void pauseWorkers_marks_workers_as_pausing_if_some_tasks_in_progress() {
    submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(12)));
    db.getDbClient().ceQueueDao().peek(session, WORKER_UUID);
    // task is in-progress

    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.RESUMED);

    underTest.pauseWorkers();
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.PAUSING);
  }

  @Test
  public void resumeWorkers_does_nothing_if_not_paused() {
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.RESUMED);

    underTest.resumeWorkers();

    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.RESUMED);
  }

  @Test
  public void resumeWorkers_resumes_pausing_workers() {
    submit(CeTaskTypes.REPORT, newComponent(randomAlphabetic(12)));
    db.getDbClient().ceQueueDao().peek(session, WORKER_UUID);
    // task is in-progress

    underTest.pauseWorkers();
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.PAUSING);

    underTest.resumeWorkers();
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.RESUMED);
  }

  @Test
  public void resumeWorkers_resumes_paused_workers() {
    underTest.pauseWorkers();
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.PAUSED);

    underTest.resumeWorkers();
    assertThat(underTest.getWorkersPauseStatus()).isEqualTo(CeQueue.WorkersPauseStatus.RESUMED);
  }

  private void verifyCeTask(CeTaskSubmit taskSubmit, CeTask task, @Nullable ComponentDto componentDto) {
    verifyCeTask(taskSubmit, task, componentDto, componentDto);
  }

  private void verifyCeTask(CeTaskSubmit taskSubmit, CeTask task, @Nullable ComponentDto componentDto, @Nullable ComponentDto mainComponentDto) {
    if (componentDto == null) {
      assertThat(task.getOrganizationUuid()).isEqualTo(defaultOrganizationProvider.get().getUuid());
    } else {
      assertThat(task.getOrganizationUuid()).isEqualTo(componentDto.getOrganizationUuid());
    }
    assertThat(task.getUuid()).isEqualTo(taskSubmit.getUuid());
    if (componentDto != null) {
      CeTask.Component component = task.getComponent().get();
      assertThat(component.getUuid()).isEqualTo(componentDto.uuid());
      assertThat(component.getKey()).contains(componentDto.getDbKey());
      assertThat(component.getName()).contains(componentDto.name());
    } else if (taskSubmit.getComponent().isPresent()) {
      assertThat(task.getComponent()).contains(new CeTask.Component(taskSubmit.getComponent().get().getUuid(), null, null));
    } else {
      assertThat(task.getComponent()).isEmpty();
    }
    if (mainComponentDto != null) {
      CeTask.Component component = task.getMainComponent().get();
      assertThat(component.getUuid()).isEqualTo(mainComponentDto.uuid());
      assertThat(component.getKey()).contains(mainComponentDto.getDbKey());
      assertThat(component.getName()).contains(mainComponentDto.name());
    } else if (taskSubmit.getComponent().isPresent()) {
      assertThat(task.getMainComponent()).contains(new CeTask.Component(taskSubmit.getComponent().get().getMainComponentUuid(), null, null));
    } else {
      assertThat(task.getMainComponent()).isEmpty();
    }
    assertThat(task.getType()).isEqualTo(taskSubmit.getType());
    assertThat(task.getSubmitterUuid()).isEqualTo(taskSubmit.getSubmitterUuid());
  }

  private void verifyCeQueueDtoForTaskSubmit(CeTaskSubmit taskSubmit) {
    Optional<CeQueueDto> queueDto = db.getDbClient().ceQueueDao().selectByUuid(db.getSession(), taskSubmit.getUuid());
    assertThat(queueDto.isPresent()).isTrue();
    assertThat(queueDto.get().getTaskType()).isEqualTo(taskSubmit.getType());
    Optional<Component> component = taskSubmit.getComponent();
    if (component.isPresent()) {
      assertThat(queueDto.get().getComponentUuid()).isEqualTo(component.get().getUuid());
      assertThat(queueDto.get().getMainComponentUuid()).isEqualTo(component.get().getMainComponentUuid());
    } else {
      assertThat(queueDto.get().getComponentUuid()).isNull();
      assertThat(queueDto.get().getComponentUuid()).isNull();
    }
    assertThat(queueDto.get().getSubmitterUuid()).isEqualTo(taskSubmit.getSubmitterUuid());
    assertThat(queueDto.get().getCreatedAt()).isEqualTo(1_450_000_000_000L);
  }

  private CeTask submit(String reportType, Component component) {
    return underTest.submit(createTaskSubmit(reportType, component, null));
  }

  private CeTaskSubmit createTaskSubmit(String type) {
    return createTaskSubmit(type, null, null);
  }

  private CeTaskSubmit createTaskSubmit(String type, @Nullable Component component, @Nullable String submitterUuid) {
    return underTest.prepareSubmit()
      .setType(type)
      .setComponent(component)
      .setSubmitterUuid(submitterUuid)
      .setCharacteristics(emptyMap())
      .build();
  }

  private ComponentDto insertComponent(ComponentDto componentDto) {
    db.getDbClient().componentDao().insert(session, componentDto);
    session.commit();
    return componentDto;
  }

  private CeQueueDto insertPendingInQueue(@Nullable Component component) {
    CeQueueDto dto = new CeQueueDto()
      .setUuid(UuidFactoryFast.getInstance().create())
      .setTaskType("some type")
      .setStatus(CeQueueDto.Status.PENDING);
    if (component != null) {
      dto.setComponentUuid(component.getUuid())
        .setMainComponentUuid(component.getMainComponentUuid());
    }
    db.getDbClient().ceQueueDao().insert(db.getSession(), dto);
    db.commit();
    return dto;
  }

  private static int newComponentIdGenerator = new Random().nextInt(8_999_333);
  private static Component newComponent(String mainComponentUuid) {
    return new Component("uuid_" + newComponentIdGenerator++, mainComponentUuid);
  }
}
