package com.signalframe.jobs.domain;

public interface JobQueue {
  void enqueue(Runnable task);
}
