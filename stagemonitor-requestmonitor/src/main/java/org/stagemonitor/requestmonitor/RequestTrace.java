package org.stagemonitor.requestmonitor;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.uber.jaeger.utils.Utils;

import org.stagemonitor.core.MeasurementSession;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.core.util.JsonUtils;
import org.stagemonitor.core.util.StringUtils;
import org.stagemonitor.requestmonitor.profiler.CallStackElement;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.opentracing.NoopTracer;
import io.opentracing.Span;
import io.opentracing.tag.Tags;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * A request trace is a data structure containing all the important information about a request.
 *
 * @deprecated use {@link io.opentracing.Span}
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Deprecated
public class RequestTrace {

	@JsonIgnore
	private final RequestMonitorPlugin requestMonitorPlugin;

	private final String id;
	private String name;
	@JsonIgnore
	private CallStackElement callStack;
	private long executionTime;
	private long executionTimeCpu;
	private boolean error = false;
	@JsonProperty("@timestamp")
	private final String timestamp;
	@JsonIgnore
	private long timestampEnd;
	private Map<String, String> parameters;
	@JsonProperty("measurement_start")
	private final long measurementStart;
	private final String application;
	private final String host;
	private final String instance;
	private String exceptionMessage;
	private String exceptionClass;
	private String exceptionStackTrace;
	private String username;
	private String disclosedUserName;
	private String clientIp;
	private String uniqueVisitorId;
	private Map<String, Object> customProperties = new HashMap<String, Object>();
	private Map<String, ExternalRequestStats> externalRequestStats = new HashMap<String, ExternalRequestStats>();
	@JsonIgnore
	private List<ExternalRequest> externalRequests = new LinkedList<ExternalRequest>();
	@JsonIgnore
	protected Span span = new NoopTracer().buildSpan(null).start();
	@JsonIgnore
	private long executionTimeNanos;
	@JsonIgnore
	private long executionTimeCpuNanos;

	public RequestTrace(String requestId) {
		this(requestId, Stagemonitor.getMeasurementSession(), Stagemonitor.getPlugin(RequestMonitorPlugin.class));
	}

	public RequestTrace(String requestId, MeasurementSession measurementSession, RequestMonitorPlugin requestMonitorPlugin) {
		this.requestMonitorPlugin = requestMonitorPlugin;
		this.id = requestId != null ? requestId : UUID.randomUUID().toString();
		this.measurementStart = measurementSession.getStartTimestamp();
		this.application = measurementSession.getApplicationName();
		this.host = measurementSession.getHostName();
		this.instance = measurementSession.getInstanceName();
		this.timestamp = StringUtils.dateAsIsoString(new Date());
	}

	public boolean isError() {
		return error;
	}

	public void setError(boolean failed) {
		Tags.ERROR.set(span, failed);
		this.error = failed;
	}

	public String getStatus() {
		return error ? "Error" : "OK";
	}

	public String getId() {
		return id;
	}

	public CallStackElement getCallStack() {
		return callStack;
	}

	public void setCallStack(CallStackElement callStack) {
		this.callStack = callStack;
	}

	@JsonProperty("callStack")
	public String getCallStackAscii() {
		if (callStack == null) {
			return null;
		}
		return callStack.toString(true);
	}

	public String getCallStackJson() {
		return JsonUtils.toJson(callStack);
	}

	/**
	 * The name of the request (e.g. 'Show Item Detail').
	 * <p/>
	 * If the name is not set when the requests ends, it won't be considered for the measurements and reportings.
	 *
	 * @return The name of the request
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the name of the request (e.g. 'Show Item Detail'). The name can be overridden and set any time.
	 *
	 * @param name the name of the request
	 */
	public void setName(String name) {
		span.setOperationName(name);
		this.name = name;
	}

	public long getExecutionTime() {
		return executionTime;
	}

	public void setExecutionTime(long executionTime) {
		this.executionTime = executionTime;
		timestampEnd = System.currentTimeMillis();
	}

	public long getExecutionTimeCpu() {
		return executionTimeCpu;
	}

	public void setExecutionTimeCpu(long executionTimeCpu) {
		this.executionTimeCpu = executionTimeCpu;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public Map<String, String> getParameters() {
		return parameters;
	}

	public void setParameters(Map<String, String> parameters) {
		this.parameters = parameters;
		for (Map.Entry<String, String> entry : parameters.entrySet()) {
			span.setTag("paremeters." + entry.getKey(), entry.getValue());
		}
	}

	public String getApplication() {
		return application;
	}

	public String getHost() {
		return host;
	}

	public String getInstance() {
		return instance;
	}

	public String getExceptionMessage() {
		return exceptionMessage;
	}

	public void setExceptionMessage(String exceptionMessage) {
		this.exceptionMessage = exceptionMessage;
	}

	public String getExceptionStackTrace() {
		return exceptionStackTrace;
	}

	public void setExceptionStackTrace(String exceptionStackTrace) {
		this.exceptionStackTrace = exceptionStackTrace;
	}

	public String getExceptionClass() {
		return exceptionClass;
	}

	public void setExceptionClass(String exceptionClass) {
		this.exceptionClass = exceptionClass;
	}

	public void setException(Exception e) {
		if (e == null || requestMonitorPlugin.getIgnoreExceptions().contains(e.getClass().getName())) {
			return;
		}
		error = true;
		Throwable throwable = e;
		if (requestMonitorPlugin.getUnnestExceptions().contains(throwable.getClass().getName())) {
			Throwable cause = throwable.getCause();
			if (cause != null) {
				throwable = cause;
			}
		}
		exceptionMessage = throwable.getMessage();
		exceptionClass = throwable.getClass().getCanonicalName();
		span.setTag("exception.message", throwable.getMessage());
		span.setTag("exception.class", throwable.getClass().getName());

		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw, true);
		throwable.printStackTrace(pw);
		exceptionStackTrace = sw.getBuffer().toString();
		span.setTag("exception.stackTrace", exceptionStackTrace);
	}

	public void setUsername(String username) {
		this.username = username;
		span.setTag("username", username);
	}

	void setDisclosedUserName(String disclosedUserName) {
		this.disclosedUserName = disclosedUserName;
		span.setTag("disclosedUserName", username);
	}

	public void setClientIp(String clientIp) {
		this.clientIp = clientIp;
		try {
			final InetAddress inetAddress = InetAddress.getByName(clientIp);
			if (inetAddress instanceof Inet4Address) {
				Tags.PEER_HOST_IPV4.set(span, clientIp != null ? Utils.ipToInt(clientIp) : null);
				span.setTag("peer.ipv4_string", clientIp);
			} else if (inetAddress instanceof Inet6Address) {
				Tags.PEER_HOST_IPV6.set(span, clientIp);
			}
		} catch (UnknownHostException e) {
			// ignore
		}
	}

	public String getUsername() {
		return username;
	}

	public String getDisclosedUserName() {
		return disclosedUserName;
	}

	public String getClientIp() {
		return clientIp;
	}

	public long getTimestampEnd() {
		return timestampEnd;
	}

	public String toJson() {
		return JsonUtils.toJson(this, "callStack");
	}

	@Override
	public String toString() {
		return toString(false);
	}

	public final String toString(boolean asciiArt) {
		return toString(asciiArt, true);
	}

	public String toString(boolean asciiArt, boolean callStack) {
		StringBuilder sb = new StringBuilder(3000);
		sb.append("id:     ").append(id).append('\n');
		sb.append("name:   ").append(getName()).append('\n');
		if (getParameters() != null) {
			sb.append("params: ").append(getParameters()).append('\n');
		}
		if (callStack) {
			appendCallStack(sb, asciiArt);
		}
		return sb.toString();
	}

	protected void appendCallStack(StringBuilder sb, boolean asciiArt) {
		if (getCallStack() != null) {
			sb.append(getCallStack().toString(asciiArt));
		}
	}

	public long getMeasurementStart() {
		return measurementStart;
	}

	@JsonAnyGetter
	public Map<String, Object> getCustomProperties() {
		return customProperties;
	}

	/**
	 * Use this method to add a custom property to this request trace.
	 * <p/>
	 * You can use these properties in the Kibana dashboard.
	 *
	 * @param key   The key, which must not contain dots (.).
	 * @param value The value, which has to be serializable by jackson.
	 */
	@JsonAnySetter
	public void addCustomProperty(String key, Object value) {
		span.setTag(key, String.valueOf(value));
		customProperties.put(key, value);
	}

	public String getUniqueVisitorId() {
		return uniqueVisitorId;
	}

	public void setUniqueVisitorId(String uniqueVisitorId) {
		span.setTag("tracking.uniqueVisitorId", uniqueVisitorId);
		this.uniqueVisitorId = uniqueVisitorId;
	}

	public List<ExternalRequest> getExternalRequests() {
		return externalRequests;
	}

	public void addExternalRequest(ExternalRequest externalRequest) {
		externalRequest.setRequestTrace(this);
		final ExternalRequestStats stats = this.externalRequestStats.get(externalRequest.getRequestType());
		if (stats == null) {
			externalRequestStats.put(externalRequest.getRequestType(), new ExternalRequestStats(externalRequest));
		} else {
			stats.add(externalRequest);
		}
		externalRequests.add(externalRequest);
	}

	public void addTimeToExternalRequest(ExternalRequest externalRequest, long additionalExecutionTime) {
		externalRequest.incrementExecutionTime(additionalExecutionTime);
		final ExternalRequestStats externalRequestStats = this.externalRequestStats.get(externalRequest.getRequestType());
		if (externalRequestStats != null) {
			externalRequestStats.incrementExecutionTime(additionalExecutionTime);
		}
		final CallStackElement callStackElement = externalRequest.getCallStackElement();
		if (callStackElement != null) {
			callStackElement.incrementExecutionTime(additionalExecutionTime);
		}
	}

	public Collection<ExternalRequestStats> getExternalRequestStats() {
		return externalRequestStats.values();
	}

	@Override
	public void finalize() throws Throwable {
		super.finalize();
		callStack.recycle();
	}

	public void setSpan(Span span) {
		this.span = span;
	}


	public void setExecutionTimeNanos(long executionTimeNanos) {
		this.executionTimeNanos = executionTimeNanos;
	}

	public long getExecutionTimeNanos() {
		return executionTimeNanos;
	}

	public void setExecutionTimeCpuNanos(long executionTimeCpuNanos) {
		span.setTag("duration_cpu", NANOSECONDS.toMicros(executionTimeCpu));
		this.executionTimeCpuNanos = executionTimeCpuNanos;
	}

	public long getExecutionTimeCpuNanos() {
		return executionTimeCpuNanos;
	}
}
