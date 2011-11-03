package cws.core.datacenter;

import static org.junit.Assert.assertEquals;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.junit.Test;

import cws.core.Cloud;
import cws.core.Job;
import cws.core.VM;
import cws.core.WorkflowEvent;
import cws.core.dag.DAG;
import cws.core.dag.DAGParser;
import cws.core.dag.Task;




public class CloudTest {
	
	private static String OUTPUT_PATH = "output";

	
	
	/**
	 * This cloud client schedules multiple DAGs
	 * on statically provisioned set of VMs.
	 * Each DAG has a priority assigned.
	 * Client keeps a priority queue of eligible jobs
	 * from all the workflows and submits them to free VMs.
	 *
	 * @author malawski
	 *
	 */
	private class CloudEnsembleDAGClient extends CloudDAGClient {
		

		// each job has a priority
		private Map<Job, Integer> jobPriorities;
		// each job is assigned to a workflow
		private Map<Job,Workflow> workflows;
		
		
		/**
		 * 
		 * Tasks IDs are not globally unique, so we need to group them into workflows.
		 * This class provides mapping from tasks to jobs.
		 *
		 */
		private class Workflow {
			
			protected Map<Task,Job> tasks2jobs;
			
			public Workflow() {
				tasks2jobs = new HashMap<Task, Job>();
			}
			
			public void add(Task task, Job job) {
				tasks2jobs.put(task, job);
			}
			
			public Job get(Task task) {
				return tasks2jobs.get(task);
			}
			
		}
		
		/**
		 * Compares jobs based on their priority
		 */
		private class JobComparator implements Comparator<Job> {

			@Override
			public int compare(Job o1, Job o2) {
				return jobPriorities.get(o1) - jobPriorities.get(o2);
			}
			
		}
		

		public CloudEnsembleDAGClient(String name) {
			super(name);
			jobPriorities = new HashMap<Job, Integer>();
			
			// here we use a priority queue instead of FIFO queue
			eligibleJobs = new PriorityQueue<Job>(64, new JobComparator());
			
			workflows = new HashMap<Job, Workflow>();
		}
		
		public Map<Job, Integer> getJobPriorities() {
			return jobPriorities;
		}
		
		public void addDAG(DAG dag, int priority) {
			Workflow workflow = new Workflow();
			String[] tasks = dag.getTasks();
			for (int i = 0; i< tasks.length; i++) {
				Task task = dag.getTask(tasks[i]);
				Job job = createJob(task);
				workflow.add(task, job);
				workflows.put(job, workflow);
				jobPriorities.put(job, priority);
				if (task.parents.isEmpty())
					eligibleJobs.add(job);
			}
		}	
		
		protected void completeJob(Job job) {
			Log.printLine(CloudSim.clock() + " Job " + job.getID() + " finished on VM " + job.getVM().getId());
			
			VM vm = job.getVM();
	    	busyVMs.remove(vm);
	    	freeVMs.add(vm);
	    	runningJobs.remove(job);
	    	completedJobs.add(job);
	    	
	    	// update eligibility
	    	for (Task child: job.getTask().children) {
	    		Set<Job> parents = new HashSet<Job>();
	    		Workflow workflow = workflows.get(job);
	    		for (Task parent : child.parents) {
	    			parents.add(workflow.get(parent));
	    		}
	    		if (completedJobs.containsAll(parents))	eligibleJobs.add(workflow.get(child));
	    	}
	    	
	    	scheduleJobs();
		}	
		
	}
	
	
	
	/**
	 * This cloud client adds functionality of dynamically provisioning 
	 * and deprovisioning VMs using very aggressive algorithm
	 * 
	 * @author malawski
	 *
	 */
	private class CloudDeprovisionerDAGClient extends CloudDAGClient {
		
		public CloudDeprovisionerDAGClient(String name) {
			super(name);
		}
		
		@Override
		public void startEntity() {
			submitVMs(vms);
			newJobs.addAll(jobs);
			// we do not want to create new entities during initialization
			// scheduleJobs();
		}
		
		@Override
		protected void provisionVMs() {
					
			// provisioning
			if (eligibleJobs.size() > freeVMs.size() + submittedVMs.size()) {
				HashSet<VM> vms = new HashSet<VM>();
				VM vm = new VM(1000, 1, 1.0, 1.0);
				vms.add(vm);
				submitVMs(vms);
			}
			
			//deprovisioning
			if(eligibleJobs.size() < freeVMs.size()) {
				for (Iterator<VM> it = freeVMs.iterator(); it.hasNext();) {
					VM vm = it.next();
					shuttingVMs.add(vm);
					it.remove();
					Log.printLine(CloudSim.clock() + "Terminating VM " + vm.getId());
					sendNow(cloud.getId(), VM_TERMINATE, vm);
				}
			}	
		}
	}
	
	
	
	
	/**
	 * This cloud client schedules DAGs by submitting only eligible jobs 
	 * (the ones which have dependencies satisfied) to VMs.
	 * 
	 * @author malawski
	 *
	 */
	private class CloudDAGClient extends CloudClient {

		protected Queue<Job> eligibleJobs;
		protected Map<Task,Job> tasks2jobs;
		
		
		public CloudDAGClient(String name) {
			super(name);
			eligibleJobs = new LinkedList<Job>();
			tasks2jobs = new HashMap<Task, Job>();
		}

		public void setDAG(DAG dag) {
			String[] tasks = dag.getTasks();
			for (int i = 0; i< tasks.length; i++) {
				Task task = dag.getTask(tasks[i]);
				Job job = createJob(task);
				tasks2jobs.put(task, job);
				if (task.parents.isEmpty())
					eligibleJobs.add(job);
			}
		}
		
		/**
		 * Job creation
		 * Create job object based on a task from DAG
		 */
		protected Job createJob(Task task) {
			// we assume that the execution times in seconds are measured on
			// 1000 MIPS processors
			double mi = 1000.0 * task.size;
			Job job = new Job((int) mi);
			job.setTask(task);
			jobs.add(job);
			return job;
		}
		
		@Override
		protected void scheduleJobs() {
			
			provisionVMs();
			
			// if there is nothing to do, just return
			if (freeVMs.isEmpty()) return;
			if (newJobs.isEmpty()) return;

			Iterator<VM> vmIt = freeVMs.iterator();
			while (!eligibleJobs.isEmpty() && vmIt.hasNext()) {
				Job job = eligibleJobs.poll(); // retrieve and remove job from eligible set
				VM vm = vmIt.next();
				job.setVM(vm);
				vmIt.remove(); // remove VM from free set
				busyVMs.add(vm);
				submittedJobs.add(job);
				Log.printLine(CloudSim.clock() + " Submitting job " + job.getID() + " to VM " + job.getVM().getId());
				sendNow(vm.getId(), JOB_SUBMIT, job);				
			}		
		}
		
		protected void completeJob(Job job) {
			Log.printLine(CloudSim.clock() + " Job " + job.getID() + " finished on VM " + job.getVM().getId());
			
			VM vm = job.getVM();
	    	busyVMs.remove(vm);
	    	freeVMs.add(vm);
	    	runningJobs.remove(job);
	    	completedJobs.add(job);
	    	
	    	// update eligibility
	    	for (Task child: job.getTask().children) {
	    		Set<Job> parents = new HashSet<Job>();
	    		for (Task parent : child.parents) {
	    			parents.add(tasks2jobs.get(parent));
	    		}
	    		if (completedJobs.containsAll(parents))	eligibleJobs.add(tasks2jobs.get(child));
	    	}
	    	
	    	scheduleJobs();
		}	
	}
	
	
	/**
	 * The client class which acts as workflow engine. This one does not handle any dependencies, 
	 * just submits jobs to free VMs.
	 * 
	 * @author malawski
	 *
	 */
	private class CloudClient extends SimEntity implements WorkflowEvent {

		protected Cloud cloud;
		/** The set of VMs. */
		protected Set<VM> vms;
		
		/** The set of submitted VMs (not running yet) */
		protected Set<VM> submittedVMs;
		
		/** The set of VMs that are running */
		protected Set<VM> runningVMs;
		
		/** The set of free VMs, i.e. the ones which are not executing any jobs (idle) */
		protected Set<VM> freeVMs;
		
		/** The set of busy VMs, i.e. the ones which execute jobs */
		protected Set<VM> busyVMs;
		
		/** The set of VMs that are shutting down */
		protected Set<VM> shuttingVMs;
		
		/** The set of terminated VMs */
		protected Set<VM> terminatedVMs;

		/** The set of new (unsubmitted) cloudlets */
		protected Set<Job> newJobs;
		
		/** The set of submitted cloudlets */
		protected Set<Job> submittedJobs;
		
		/** The set of running cloudlets */
		protected Set<Job> runningJobs;
		
		/** The set of completed cloudlets */
		protected Set<Job> completedJobs;
		

		protected Set<Job> jobs;
		
		
		protected void provisionVMs() {}
		
		public CloudClient(String name) {
			super(name);
			CloudSim.addEntity(this);
			jobs = new HashSet<Job>();
			runningVMs = new HashSet<VM>();
	        freeVMs = new HashSet<VM>();
	        busyVMs = new HashSet<VM>();
	        submittedVMs = new HashSet<VM>();
	        shuttingVMs = new HashSet<VM>();
	        terminatedVMs = new HashSet<VM>();
	        newJobs = new HashSet<Job>();
	        submittedJobs = new HashSet<Job>();
	        runningJobs = new HashSet<Job>();
	        completedJobs = new HashSet<Job>();
		}
		
		public Set<Job> getCompletedJobs() {
			return completedJobs;
		}
		
		public Set<Job> getJobs() {
			return jobs;
		}

		public void setJobs(Set<Job> jobs) {
			this.jobs = jobs;
		}

		public void setVMs(Set<VM> vms) {
			this.vms = vms;
		}

		public void setCloud(Cloud cloud) {
			this.cloud = cloud;
		}
		
		public Set<VM> getRunningVMs() {
			return runningVMs;
		}
		
		public Set<VM> getTerminatedVMs() {
			return terminatedVMs;
		}

		@Override
		public void startEntity() {
			submitVMs(vms);
			newJobs.addAll(jobs);
			scheduleJobs();
		}
		
		
		protected void submitVMs (Set<VM> vms) {
			for (VM vm : vms) {
				send(cloud.getId(), 0.0, VM_LAUNCH, vm);
				submittedVMs.add(vm);
			}
		}

		protected void scheduleJobs() {
			
			// if there is nothing to do, just return
			if (freeVMs.isEmpty()) return;
			if (newJobs.isEmpty()) return;
			Iterator<Job> jobIt = newJobs.iterator();
			Iterator<VM> vmIt = freeVMs.iterator();
			while (jobIt.hasNext() && vmIt.hasNext()) {
				Job job = jobIt.next();
				VM vm = vmIt.next();
				job.setVM(vm);
				vmIt.remove(); // remove VM from free set
				busyVMs.add(vm);
				jobIt.remove(); // remove job from new set
				submittedJobs.add(job);
				sendNow(vm.getId(), JOB_SUBMIT, job);
			}		
		}	

		@Override
		public void processEvent(SimEvent ev) { 
	        switch(ev.getTag()) {
	        case VM_LAUNCHED:
	        	vmLaunched((VM)ev.getData());
	            break;
	        case JOB_STARTED:
	        	startJob((Job)ev.getData());
	            break;
	        case JOB_FINISHED:
	        	completeJob((Job)ev.getData());
	            break;
	        case VM_TERMINATED:
	        	vmTerminationComplete((VM)ev.getData());
	        	break;
	        default:
	            throw new RuntimeException("Unknown event: "+ev);
	        }
		}
		
		protected void vmLaunched(VM vm) {
			Log.printLine(CloudSim.clock() + " VM started " + vm.getId());
            runningVMs.add(vm);
            freeVMs.add(vm);
            submittedVMs.remove(vm);
            scheduleJobs();
		}

		protected void vmTerminationComplete(VM vm) {
			Log.printLine(CloudSim.clock() + " VM terminated " + vm.getId());
			shuttingVMs.remove(vm);
			runningVMs.remove(vm);
			terminatedVMs.add(vm);
		}

		protected void startJob(Job job) {
			Log.printLine(CloudSim.clock() + " Job " + job.getID() + " started on VM " + job.getVM().getId());
			submittedJobs.remove(job);
			runningJobs.add(job);
		}
		
		protected void completeJob(Job job) {
			Log.printLine(CloudSim.clock() + " Job " + job.getID() + " finished on VM " + job.getVM().getId());
	    	VM vm = job.getVM();
	    	busyVMs.remove(vm);
	    	freeVMs.add(vm);
	    	runningJobs.remove(job);
	    	completedJobs.add(job);
	    	scheduleJobs();
		}

		@Override
		public void shutdownEntity() {	}
		
	}
	
	
	
	public static void printJobs(Set<Job> jobs, String fileName) {

		
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw, true);
		
		
		String indent = "    ";
		pw.println();
		pw.println("========== OUTPUT ==========");
		pw.println("Job ID" + indent + "STATUS" + indent
				+ "Data center ID" + indent + "VM ID" + indent + "Time" + indent
				+ "Start Time" + indent + "Finish Time");

		DecimalFormat dft = new DecimalFormat("###.##");
		for (Job job : jobs) {
			pw.print(indent + job.getID() + indent + indent);

			if (job.getState() == Job.State.SUCCESS) {
				pw.print("SUCCESS");

				pw.println(indent + indent + job.getVM().getCloud()
						+ indent + indent + indent + job.getVM().getId()
						+ indent + indent
						+ dft.format(job.getDuration()) + indent
						+ indent + dft.format(job.getStartTime())
						+ indent + indent
						+ dft.format(job.getFinishTime()));
			}
		}
		Log.print(sw.toString());
		stringToFile(sw.toString(),  fileName + ".txt");

	}
	

	public static void printEnsemble(Set<Job> jobs, Map<Job, Integer> jobPriorities, String fileName) {

		
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw, true);
		
		
		String indent = "    ";
		pw.println();
		pw.println("========== OUTPUT ==========");
		pw.println("Job ID" + indent + "STATUS" + indent
				+ "Priority" + indent + "VM ID" + indent + "Time" + indent
				+ "Start Time" + indent + "Finish Time");

		DecimalFormat dft = new DecimalFormat("###.##");
		for (Job job : jobs) {
			pw.print(indent + job.getID() + indent + indent);

			if (job.getState() == Job.State.SUCCESS) {
				pw.print("SUCCESS");

				pw.println(indent + indent + jobPriorities.get(job)
						+ indent + indent + indent + job.getVM().getId()
						+ indent + indent
						+ dft.format(job.getDuration()) + indent
						+ indent + dft.format(job.getStartTime())
						+ indent + indent
						+ dft.format(job.getFinishTime()));
			}
		}
		Log.print(sw.toString());
		stringToFile(sw.toString(),  fileName + ".txt");

	}
	
	
	public static void printVmList(Set<VM> vms, String name) {



		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw, true);
		
		
		String indent = "    ";
		pw.println();
		pw.println("========== VMs ==========");
		pw.println("VM ID" + indent + "Creation Time" + indent
				+ "Destroy Time");

		DecimalFormat dft = new DecimalFormat("###.##");

		for (VM vm : vms) {
			
			pw.print(indent + vm.getId() + indent + indent);

				pw.println(indent + indent + dft.format(vm.getLaunchTime())
						+ indent + indent
						+ dft.format(vm.getTerminateTime())
						);
			}
		
		Log.print(sw.toString());
		stringToFile(sw.toString(), name + "-vms.txt");
		
	}
	
	public static void stringToFile(String s, String fileName) {
		BufferedWriter out;
		try {
			out = new BufferedWriter(new FileWriter(OUTPUT_PATH + File.separator + fileName));
			out.write(s);
			out.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	
	}
	
	
	
    //@Test
    public void testCloudVMs() {
        CloudSim.init(1, null, false);
        
        CloudClient cloudClient = new CloudClient("Client");
        Cloud cloud  = new Cloud();
        
        cloudClient.setCloud(cloud);
        
        VM vm = new VM(1000, 1, 1.0, 1.0);
        HashSet<VM> vms = new HashSet<VM>();
        vms.add(vm);
                      
        cloudClient.setVMs(vms);
        
        CloudSim.startSimulation();   
        
        assertEquals(vms.size(), cloudClient.getRunningVMs().size());
       
    }
	
    //@Test
    public void testCloud10VMs() {
        CloudSim.init(1, null, false);
        
        CloudClient cloudClient = new CloudClient("Client");
        Cloud cloud  = new Cloud();
        
        cloudClient.setCloud(cloud);
        HashSet<VM> vms = new HashSet<VM>();
        for (int i = 0; i < 10; i++ ) {
        	VM vm = new VM(1000, 1, 1.0, 1.0);
        	vms.add(vm);
        }     
        cloudClient.setVMs(vms);
        
        CloudSim.startSimulation();   
        
        assertEquals(vms.size(), cloudClient.getRunningVMs().size());
       
    }
    

	//@Test
	public void testCloudJobs() {
		CloudSim.init(1, null, false);

		CloudClient cloudClient = new CloudClient("Client");
		Cloud cloud = new Cloud();

		cloudClient.setCloud(cloud);
		HashSet<VM> vms = new HashSet<VM>();
		for (int i = 0; i < 10; i++) {
			VM vm = new VM(1000, 1, 1.0, 1.0);
			vms.add(vm);
		}

		HashSet<Job> jobs = new HashSet<Job>();

		for (int i = 0; i < 100; i++) {
			Job job = new Job(1000);
			jobs.add(job);
		}
		cloudClient.setVMs(vms);
		cloudClient.setJobs(jobs);

		CloudSim.startSimulation();

		assertEquals(vms.size(), cloudClient.getRunningVMs().size());
		assertEquals(jobs.size(), cloudClient.getCompletedJobs().size());

	}

	//@Test
	public void testCloudDAG100() {
		
		CloudSim.init(1, null, false);

		CloudDAGClient cloudClient = new CloudDAGClient("Client");
		Cloud cloud = new Cloud();
		cloudClient.setCloud(cloud);
		
		HashSet<VM> vms = new HashSet<VM>();
		for (int i = 0; i < 10; i++) {
			VM vm = new VM(1000, 1, 1.0, 1.0);
			vms.add(vm);
		}

		DAG dag = DAGParser.parseDAG(new File("dags/CyberShake_100.dag"));
		
		cloudClient.setVMs(vms);
		cloudClient.setDAG(dag);

		CloudSim.startSimulation();

		assertEquals(vms.size(), cloudClient.getRunningVMs().size());
		assertEquals(dag.numTasks(), cloudClient.getCompletedJobs().size());
		
		printJobs(cloudClient.getCompletedJobs(), "CyberShake_100");

	}
	
	
	public void runCloudDeprovisioner(String dagPath, String outputName) {

        CloudSim.init(1, null, false);
        
		// try {
		// Log.setOutput(new FileOutputStream(new
		// File("testDatacenterDeprovisionerDAG.log")));
		// } catch (FileNotFoundException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }


		CloudDeprovisionerDAGClient cloudClient = new CloudDeprovisionerDAGClient("Client");
		Cloud cloud = new Cloud();
		cloudClient.setCloud(cloud);
		
		HashSet<VM> vms = new HashSet<VM>();
		VM vm = new VM(1000, 1, 1.0, 1.0);
		vms.add(vm);


		DAG dag = DAGParser.parseDAG(new File(dagPath));
		
		cloudClient.setVMs(vms);
		cloudClient.setDAG(dag);

		CloudSim.startSimulation();

		assertEquals(0, cloudClient.getRunningVMs().size());
		assertEquals(dag.numTasks(), cloudClient.getCompletedJobs().size());
		
		printJobs(cloudClient.getCompletedJobs(), outputName);
		printVmList(cloudClient.getTerminatedVMs(), outputName);
	}
	
	
    //@Test
    public void testCloudDeprovisioner100DAG() {
    	runCloudDeprovisioner("dags/CyberShake_100.dag", "DeprovisionerCyberShake_100");        
    }

    //@Test
    public void testCloudDeprovisionerCybershakeDAG() {
    	runCloudDeprovisioner("dags/cybershake_small.dag", "Deprovisionercybershake_small");        
    }
    
    
	//@Test
	public void testEnsemble() {
		
		CloudSim.init(1, null, false);

		CloudEnsembleDAGClient cloudClient = new CloudEnsembleDAGClient("Client");
		Cloud cloud = new Cloud();
		cloudClient.setCloud(cloud);
		
		HashSet<VM> vms = new HashSet<VM>();
		for (int i = 0; i < 10; i++) {
			VM vm = new VM(1000, 1, 1.0, 1.0);
			vms.add(vm);
		}
		cloudClient.setVMs(vms);
		
		DAG dag;
		dag = DAGParser.parseDAG(new File("dags/CyberShake_30.dag"));
		

		int priority = 1;
		cloudClient.addDAG(dag, priority);

		CloudSim.startSimulation();

		assertEquals(vms.size(), cloudClient.getRunningVMs().size());
		assertEquals(dag.numTasks(), cloudClient.getCompletedJobs().size());
		
		printJobs(cloudClient.getCompletedJobs(), "EnsembleCyberShake_30");

	}
    
	@Test
	public void testEnsemble30_50() {
		
		CloudSim.init(1, null, false);

		CloudEnsembleDAGClient cloudClient = new CloudEnsembleDAGClient("Client");
		Cloud cloud = new Cloud();
		cloudClient.setCloud(cloud);
		
		HashSet<VM> vms = new HashSet<VM>();
		for (int i = 0; i < 10; i++) {
			VM vm = new VM(1000, 1, 1.0, 1.0);
			vms.add(vm);
		}
		cloudClient.setVMs(vms);
		
		int priority;
		
		DAG dag1 = DAGParser.parseDAG(new File("dags/CyberShake_30.dag"));
		priority = 1;
		cloudClient.addDAG(dag1, priority);

		DAG dag2 = DAGParser.parseDAG(new File("dags/CyberShake_50.dag"));
		priority = 2;
		cloudClient.addDAG(dag2, priority);
		
		CloudSim.startSimulation();

		assertEquals(vms.size(), cloudClient.getRunningVMs().size());
		assertEquals(dag1.numTasks() + dag2.numTasks(), cloudClient.getCompletedJobs().size());
		
		printEnsemble(cloudClient.getCompletedJobs(), cloudClient.getJobPriorities(), "EnsembleCyberShake_30_50");

	}
    

	@Test
	public void testEnsemble10x100() {
		
		CloudSim.init(1, null, false);

		CloudEnsembleDAGClient cloudClient = new CloudEnsembleDAGClient("Client");
		Cloud cloud = new Cloud();
		cloudClient.setCloud(cloud);
		
		HashSet<VM> vms = new HashSet<VM>();
		for (int i = 0; i < 10; i++) {
			VM vm = new VM(1000, 1, 1.0, 1.0);
			vms.add(vm);
		}
		cloudClient.setVMs(vms);
		
		int totalTasks = 0;
		
		for (int priority =0; priority <10; priority++) {
			DAG dag = DAGParser.parseDAG(new File("dags/CyberShake_100.dag"));
			cloudClient.addDAG(dag, priority);
			totalTasks += dag.numTasks();
		}

		
		CloudSim.startSimulation();

		assertEquals(vms.size(), cloudClient.getRunningVMs().size());
		assertEquals(totalTasks, cloudClient.getCompletedJobs().size());
		
		printEnsemble(cloudClient.getCompletedJobs(), cloudClient.getJobPriorities(), "EnsembleCyberShake_10x100");

	}
	
	
	public void runEnsembleMxN(int M, int N, String DAGName) {
		
		CloudSim.init(1, null, false);

		CloudEnsembleDAGClient cloudClient = new CloudEnsembleDAGClient("Client");
		Cloud cloud = new Cloud();
		cloudClient.setCloud(cloud);
		
		HashSet<VM> vms = new HashSet<VM>();
		for (int i = 0; i < M; i++) {
			VM vm = new VM(1000, 1, 1.0, 1.0);
			vms.add(vm);
		}
		cloudClient.setVMs(vms);
		
		int totalTasks = 0;
		
		for (int priority = 0; priority <N; priority++) {
			DAG dag = DAGParser.parseDAG(new File("dags/" + DAGName));
			cloudClient.addDAG(dag, priority);
			totalTasks += dag.numTasks();
		}

		
		CloudSim.startSimulation();

		assertEquals(vms.size(), cloudClient.getRunningVMs().size());
		assertEquals(totalTasks, cloudClient.getCompletedJobs().size());
		
		printEnsemble(cloudClient.getCompletedJobs(), cloudClient.getJobPriorities(), DAGName + M + "x" + N);

	}
	
	@Test
	public void testEnsemble10x20x100() {
		runEnsembleMxN(10, 20, "CyberShake_100.dag");
	}
	
	@Test
	public void testEnsemble20x10x100() {
		runEnsembleMxN(20, 10, "CyberShake_100.dag");
	}
	
	@Test
	public void testEnsemble20x9x100() {
		runEnsembleMxN(20, 9, "CyberShake_100.dag");
	}

	@Test
	public void testEnsemble20x10x1000() {
		runEnsembleMxN(20, 10, "CyberShake_1000.dag");
	}
	
	@Test
	public void testEnsemble200x10x1000() {
		runEnsembleMxN(200, 10, "CyberShake_1000.dag");
	}

	//@Test
	public void testEnsemble256x10xsmall() {
		runEnsembleMxN(256, 10, "cybershake_small.dag");
	}
	
	//@Test
	public void testEnsemble2560x10xsmall() {
		runEnsembleMxN(2560, 10, "cybershake_small.dag");
	}

	
	
}