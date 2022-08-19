package services.k_int.core;

import java.time.Duration

import javax.annotation.PostConstruct;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class SystemTaskSchedulerService {
  private final ThreadPoolTaskScheduler taskScheduler;

  public SystemTaskSchedulerService() {
    taskScheduler = new ThreadPoolTaskScheduler()
    taskScheduler.setPoolSize(1)
    taskScheduler.setThreadGroupName('_system')
    taskScheduler.setThreadNamePrefix('SystemTask')
    
    taskScheduler.setDaemon(true)
    taskScheduler.initialize();
  }

  public void runTaskEvery ( final Duration time, final Closure work) {
    
    if (true /* lock */) {
      this.taskScheduler.scheduleWithFixedDelay(work, time)
    }
  }
}
