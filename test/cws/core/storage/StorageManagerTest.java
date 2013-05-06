package cws.core.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;

import cws.core.VM;
import cws.core.WorkflowEvent;
import cws.core.cloudsim.CloudSimWrapper;
import cws.core.dag.DAGFile;
import cws.core.dag.Task;
import cws.core.exception.UnknownWorkflowEventException;
import cws.core.jobs.Job;

/**
 * Tests {@link StorageManager}. An abstract class - should be subclassed and field {@link #storageManager} should be
 * initialized with correct subclass.
 */
public abstract class StorageManagerTest {
    protected Random random;
    protected StorageManager storageManager;
    protected CloudSimWrapper cloudsim;
    protected Job job;
    protected VM vm;
    protected Task task;

    @Before
    public void setUpStorageManagerTest() {
        cloudsim = Mockito.spy(new CloudSimWrapper());
        CloudSim.init(1, null, false);
        random = new Random(7);

        job = Mockito.mock(Job.class);
        vm = Mockito.mock(VM.class);
        Mockito.when(vm.getId()).thenReturn(100);
        job.setVM(vm);
        Mockito.when(job.getVM()).thenReturn(vm);
        task = Mockito.mock(Task.class);
        Mockito.when(job.getTask()).thenReturn(task);
    }

    @Test
    public void testEmptySimulation() {
        CloudSim.startSimulation();
    }

    @Test
    public void testBeforeTaskStartOnJobWithNoFiles() {
        Mockito.when(task.getInputFiles()).thenReturn(new ArrayList<DAGFile>());
        skipEvent(100, WorkflowEvent.STORAGE_ALL_BEFORE_TRANSFERS_COMPLETED);
        CloudSim.send(-1, storageManager.getId(), random.nextDouble(), WorkflowEvent.STORAGE_BEFORE_TASK_START, job);
        CloudSim.startSimulation();

        Mockito.verify(cloudsim).send(Matchers.anyInt(), Matchers.eq(100), Matchers.anyDouble(),
                Matchers.eq(WorkflowEvent.STORAGE_ALL_BEFORE_TRANSFERS_COMPLETED), Matchers.any());
    }

    @Test
    public void testAfterTaskCompletedOnJobWithNoFiles() {
        Mockito.when(task.getOutputFiles()).thenReturn(new ArrayList<DAGFile>());
        skipEvent(100, WorkflowEvent.STORAGE_ALL_AFTER_TRANSFERS_COMPLETED);
        CloudSim.send(-1, storageManager.getId(), random.nextDouble(), WorkflowEvent.STORAGE_AFTER_TASK_COMPLETED, job);
        CloudSim.startSimulation();

        Mockito.verify(cloudsim).send(Matchers.anyInt(), Matchers.eq(100), Matchers.anyDouble(),
                Matchers.eq(WorkflowEvent.STORAGE_ALL_AFTER_TRANSFERS_COMPLETED), Matchers.any());
    }

    @Test
    public void testBeforeTaskStartOnJobWithTwoFiles() {
        List<DAGFile> files = new ArrayList<DAGFile>();
        files.add(new DAGFile("abc.txt", 2442));
        files.add(new DAGFile("def.txt", 327879));
        Mockito.when(task.getInputFiles()).thenReturn(files);
        skipEvent(100, WorkflowEvent.STORAGE_ALL_BEFORE_TRANSFERS_COMPLETED);
        CloudSim.send(-1, storageManager.getId(), random.nextDouble(), WorkflowEvent.STORAGE_BEFORE_TASK_START, job);
        CloudSim.startSimulation();

        Mockito.verify(cloudsim).send(Matchers.anyInt(), Matchers.eq(100), Matchers.anyDouble(),
                Matchers.eq(WorkflowEvent.STORAGE_ALL_BEFORE_TRANSFERS_COMPLETED), Matchers.any());
    }

    @Test
    public void testAfterTaskCompletedOnJobWithTwoFiles() {
        List<DAGFile> files = new ArrayList<DAGFile>();
        files.add(new DAGFile("abc.txt", 2442));
        files.add(new DAGFile("def.txt", 327879));
        Mockito.when(task.getOutputFiles()).thenReturn(files);
        skipEvent(100, WorkflowEvent.STORAGE_ALL_AFTER_TRANSFERS_COMPLETED);
        CloudSim.send(-1, storageManager.getId(), random.nextDouble(), WorkflowEvent.STORAGE_AFTER_TASK_COMPLETED, job);
        CloudSim.startSimulation();
        Mockito.verify(cloudsim).send(Matchers.anyInt(), Matchers.eq(100), Matchers.anyDouble(),
                Matchers.eq(WorkflowEvent.STORAGE_ALL_AFTER_TRANSFERS_COMPLETED), Matchers.any());
    }

    @Test(expected = UnknownWorkflowEventException.class)
    public void testUnknownMsg() {
        CloudSim.send(storageManager.getId(), storageManager.getId(), random.nextDouble(), 21434243, null);
        CloudSim.startSimulation();
    }

    /** Skips event sent to by cloudsim obj. The rest is forwarded to the underlying CloudSim. */
    public void skipEvent(int dst, int event) {
        Mockito.doNothing().when(cloudsim)
                .send(Matchers.anyInt(), Matchers.eq(dst), Matchers.anyDouble(), Matchers.eq(event), Matchers.any());
    }
}