/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.engine.loader;

import ai.grakn.engine.backgroundtasks.TaskStatus;
import ai.grakn.engine.backgroundtasks.distributed.ClusterManager;
import ai.grakn.engine.backgroundtasks.distributed.DistributedTaskManager;
import ai.grakn.engine.util.ConfigProperties;
import ai.grakn.graql.InsertQuery;
import ai.grakn.util.ErrorMessage;
import javafx.util.Pair;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static ai.grakn.engine.backgroundtasks.TaskStatus.COMPLETED;
import static ai.grakn.engine.backgroundtasks.TaskStatus.CREATED;
import static ai.grakn.engine.backgroundtasks.TaskStatus.FAILED;
import static ai.grakn.engine.backgroundtasks.TaskStatus.RUNNING;
import static ai.grakn.engine.backgroundtasks.TaskStatus.SCHEDULED;
import static ai.grakn.engine.util.ConfigProperties.BATCH_SIZE_PROPERTY;
import static ai.grakn.util.REST.Request.KEYSPACE_PARAM;
import static ai.grakn.util.REST.Request.TASK_LOADER_INSERTS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * Manage loading tasks in the Task Manager
 *
 * @author Alexandra Orth
 */
public class Loader {

    private static final Logger LOG = LoggerFactory.getLogger(Loader.class);
    private static final ConfigProperties properties = ConfigProperties.getInstance();

    private final DistributedTaskManager manager;
    private Semaphore blocker = new Semaphore(25);

    private int batchSize;
    private final Collection<InsertQuery> queries;
    private final String keyspace;

    public Loader(ClusterManager manager, String keyspace){
        this.keyspace = keyspace;
        this.queries = new HashSet<>();

        this.manager = manager.getTaskManager();
        setBatchSize(properties.getPropertyAsInt(BATCH_SIZE_PROPERTY));
    }

    /**
     * @return the current batch size - minimum number of vars to be loaded in a transaction
     */
    public int getBatchSize(){
        return this.batchSize;
    }

    /**
     * Set the size of the each transaction in terms of number of vars.
     * @param size number of vars in each transaction
     */
    public Loader setBatchSize(int size){
        this.batchSize = size;
        return this;
    }

    /**
     * Set the size of the queue- this is equivalent to the size of the semaphore.
     * @param size the size of the queue
     */
    public Loader setQueueSize(int size){
        blocker = new Semaphore(size);
        return this;
    }

    /**
     * Load any remaining batches in the queue.
     */
    public void flush(){
        if(queries.size() > 0){
            sendQueriesToLoader(queries);
            queries.clear();
        }
    }

    /**
     * Add an insert query to the queue
     * @param query insert query to be executed
     */
    public void add(InsertQuery query){
        queries.add(query);
        if(queries.size() >= batchSize){
            sendQueriesToLoader(new HashSet<>(queries));
            queries.clear();
        }
    }

    /**
     * Method to load data into the graph. Implementation depends on the type of the loader.
     */
    public void sendQueriesToLoader(Collection<InsertQuery> batch){
        try {
            blocker.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        String taskId = manager.scheduleTask(new LoaderTask(), keyspace, Instant.now(), 0, getConfiguration(batch));
        CompletableFuture<?> completableFuture = manager.completableFuture(taskId);
        completableFuture.thenAccept(i -> releaseSemaphore());
        completableFuture.exceptionally(i -> {
            releaseSemaphore();
            return null;
        });
    }

    private void releaseSemaphore() {
        blocker.release();
    }

    private Boolean allTasksFinished(Collection<String> tasks) {
        printLoaderState();

        if(tasks.stream().allMatch(this::isCompleted)) {
            return true;
        }
        else {
            try {
                Thread.sleep(500);
            } catch (Exception e) {
                LOG.error("Problem sleeping.");
            }
        }

        return false;
    }

    private Map<TaskStatus,Integer> countTasks(Collection<String> tasks) {
        Map<TaskStatus, Integer> taskCounter = getTaskCounter();

        tasks.stream().map(manager::getState).forEach(state -> {
            taskCounter.computeIfPresent(state, (key, value) -> ++value);
        });

        return taskCounter;
    }

    private Map<TaskStatus,Integer> getTaskCounter() {
        Map<TaskStatus,Integer> taskCounter = new HashMap<>();
        Arrays.asList(TaskStatus.values()).forEach(key -> taskCounter.put(key,0));
        return taskCounter;
    }

    /**
     * Wait for all tasks to finish.
     * @param timeout amount of time (in ms) to wait.
     */
    public void waitToFinish(int timeout){
        flush();

        Collection<String> currentTasks = getTasks();
        Map<TaskStatus,Integer> currentTaskCounter = countTasks(currentTasks);
        Map<TaskStatus, Integer> previousTaskCounter;
        long timestamp = 0L;
        while (true) {
            if(allTasksFinished(currentTasks)) {
                printLoaderState();
                return;
            }

            previousTaskCounter = currentTaskCounter;
            currentTaskCounter = countTasks(currentTasks);
            if (currentTaskCounter.equals(previousTaskCounter) && timestamp==0L) {
                timestamp = new Date().getTime();
            } else if (!currentTaskCounter.equals(previousTaskCounter)) {
                timestamp = 0L;
            } else if (new Date().getTime() - timestamp > timeout) {
                break;
            }

            try {
                Thread.sleep(500);
            } catch (Exception e) {
                LOG.error("Problem sleeping.");
            }
        }
        throw new RuntimeException(ErrorMessage.LOADER_WAIT_TIMEOUT.getMessage());
    }

    /**
     * Method that logs the current taskstorage of loading tasks
     */
    public void printLoaderState(){
        LOG.info(new JSONObject()
                .put(CREATED.name(), getTasks(CREATED).size())
                .put(SCHEDULED.name(), getTasks(SCHEDULED).size())
                .put(RUNNING.name(), getTasks(RUNNING).size())
                .put(COMPLETED.name(), getTasks(COMPLETED).size())
                .put(FAILED.name(), getTasks(FAILED).size())
                .toString());
    }

    /**
     * Get all loading tasks for this keyspace
     * @return IDs of tasks in this keyspace
     */
    private Collection<String> getTasks(){
        return manager.storage().getTasks(null, LoaderTask.class.getName(), keyspace, 100000, 0).stream()
                .map(Pair::getKey)
                .collect(toSet());
    }

    /**
     * Get the number of loading tasks of a particular status in this keyspace
     * @param status type of task to count
     * @return number of tasks within the given parameters
     */
    private Collection<String> getTasks(TaskStatus status){
        return manager.storage().getTasks(status, LoaderTask.class.getName(), keyspace, 100000, 0).stream()
                .map(Pair::getKey)
                .collect(toSet());
    }

    /**
     * Check if a single task is completed or failed.
     * @param taskID id of the task to check
     * @return if the given task has been completed or failed.
     */
    private boolean isCompleted(String taskID){
        TaskStatus status = manager.getState(taskID);
        return status == COMPLETED || status == FAILED;
    }

    /**
     * Transform queries into Json configuration needed by the Loader task
     * @param queries queries to include in configuration
     * @return configuration for the loader task
     */
    private JSONObject getConfiguration(Collection<InsertQuery> queries){
        JSONObject json = new JSONObject();
        json.put(KEYSPACE_PARAM, keyspace);
        json.put(TASK_LOADER_INSERTS, queries.stream().map(InsertQuery::toString).collect(toList()));
        return json;
    }
}
