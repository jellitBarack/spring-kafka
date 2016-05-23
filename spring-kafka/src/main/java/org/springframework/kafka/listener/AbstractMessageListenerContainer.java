/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.config.ContainerProperties;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.util.Assert;

/**
 * The base implementation for the {@link MessageListenerContainer}.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Gary Russell
 * @author Marius Bogoevici
 */
public abstract class AbstractMessageListenerContainer<K, V>
		implements MessageListenerContainer, BeanNameAware, SmartLifecycle {

	protected final Log logger = LogFactory.getLog(this.getClass()); // NOSONAR

	/**
	 * The offset commit behavior enumeration.
	 */
	public enum AckMode {

		/**
		 * Commit after each record is processed by the listener.
		 */
		RECORD,

		/**
		 * Commit whatever has already been processed before the next poll.
		 */
		BATCH,

		/**
		 * Commit pending updates after
		 * {@link ContainerProperties#setAckTime(long) ackTime} has elapsed.
		 */
		TIME,

		/**
		 * Commit pending updates after
		 * {@link ContainerProperties#setAckCount(int) ackCount} has been
		 * exceeded.
		 */
		COUNT,

		/**
		 * Commit pending updates after
		 * {@link ContainerProperties#setAckCount(int) ackCount} has been
		 * exceeded or after {@link ContainerProperties#setAckTime(long)
		 * ackTime} has elapsed.
		 */
		COUNT_TIME,

		/**
		 * Same as {@link #COUNT_TIME} except for pending manual acks.
		 */
		MANUAL,

		/**
		 * Call {@link Consumer#commitAsync()} immediately for pending acks.
		 */
		MANUAL_IMMEDIATE,

		/**
		 * Call {@link Consumer#commitSync()} immediately for pending acks.
		 */
		MANUAL_IMMEDIATE_SYNC

	}

	private final ContainerProperties containerProperties;

	private final Object lifecycleMonitor = new Object();

	private String beanName;

	private boolean autoStartup = true;

	private int phase = 0;

	private volatile boolean running = false;

	protected AbstractMessageListenerContainer(ContainerProperties containerProperties) {
		Assert.notNull(containerProperties, "'containerProperties' cannot be null");
		this.containerProperties = containerProperties;
		if (containerProperties.consumerRebalanceListener == null) {
			containerProperties.consumerRebalanceListener = createConsumerRebalanceListener();
		}
	}

	@Override
	public void setBeanName(String name) {
		this.beanName = name;
	}

	public String getBeanName() {
		return this.beanName;
	}

	@Override
	public boolean isAutoStartup() {
		return this.autoStartup;
	}

	public void setAutoStartup(boolean autoStartup) {
		this.autoStartup = autoStartup;
	}

	protected void setRunning(boolean running) {
		this.running = running;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	public void setPhase(int phase) {
		this.phase = phase;
	}

	@Override
	public int getPhase() {
		return this.phase;
	}

	public ContainerProperties getContainerProperties() {
		return this.containerProperties;
	}

	@Override
	public void setupMessageListener(Object messageListener) {
		this.containerProperties.messageListener = messageListener;
	}

	@Override
	public final void start() {
		synchronized (this.lifecycleMonitor) {
			Assert.isTrue(
					this.containerProperties.messageListener instanceof MessageListener
							|| this.containerProperties.messageListener instanceof AcknowledgingMessageListener,
					"Either a " + MessageListener.class.getName() + " or a "
							+ AcknowledgingMessageListener.class.getName() + " must be provided");
			if (this.containerProperties.recoveryCallback == null) {
				this.containerProperties.recoveryCallback = new RecoveryCallback<Void>() {

					@Override
					public Void recover(RetryContext context) throws Exception {
						@SuppressWarnings("unchecked")
						ConsumerRecord<K, V> record = (ConsumerRecord<K, V>) context.getAttribute("record");
						Throwable lastThrowable = context.getLastThrowable();
						if (AbstractMessageListenerContainer.this.containerProperties.errorHandler != null
								&& lastThrowable instanceof Exception) {
							AbstractMessageListenerContainer.this.containerProperties.errorHandler
									.handle((Exception) lastThrowable, record);
						}
						else {
							AbstractMessageListenerContainer.this.logger.error(
									"Listener threw an exception and no error handler for " + record, lastThrowable);
						}
						return null;
					}

				};
			}
			doStart();
		}
	}

	protected abstract void doStart();

	@Override
	public final void stop() {
		final CountDownLatch latch = new CountDownLatch(1);
		stop(new Runnable() {
			@Override
			public void run() {
				latch.countDown();
			}
		});
		try {
			latch.await(this.containerProperties.shutdownTimeout, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
		}
	}

	@Override
	public void stop(Runnable callback) {
		synchronized (this.lifecycleMonitor) {
			doStop(callback);
		}
	}

	protected abstract void doStop(Runnable callback);

	/**
	 * Return default implementation of {@link ConsumerRebalanceListener} instance.
	 * @return the {@link ConsumerRebalanceListener} currently assigned to this container.
	 */
	protected final ConsumerRebalanceListener createConsumerRebalanceListener() {
		return new ConsumerRebalanceListener() {

			@Override
			public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
				AbstractMessageListenerContainer.this.logger.info("partitions revoked:" + partitions);
			}

			@Override
			public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
				AbstractMessageListenerContainer.this.logger.info("partitions assigned:" + partitions);
			}

		};
	}

}
