import groovy.json.JsonSlurper

// Cross-check: JMeter's own measured time
long jmeterTime_ms = prev.getTime()

// Parse Ollama response
def response = prev.getResponseDataAsString()
def json = new JsonSlurper().parseText(response)

// Raw Ollama timing fields (nanoseconds)
def totalDuration   = json.total_duration       as long
def loadDuration    = json.load_duration        as long
def promptEvalDur   = json.prompt_eval_duration as long
def evalDuration    = json.eval_duration        as long
def evalCount       = json.eval_count           as long
def promptEvalCount = json.prompt_eval_count    as long

// LLM KPIs
double ttft_ms           = promptEvalDur / 1_000_000.0
double tpot_ms           = (evalCount > 0) ? (evalDuration / 1_000_000.0) / evalCount : 0.0
double tokensPerSecond   = (evalDuration > 0) ? evalCount / (evalDuration / 1_000_000_000.0) : 0.0
double inferenceTime_ms  = (totalDuration - loadDuration) / 1_000_000.0
double totalTime_ms      = totalDuration / 1_000_000.0

// Stuck inference detection
def content    = json?.message?.content ?: ""
def doneReason = json?.done_reason ?: "unknown"
def isStuck    = false
def stuckReason = "none"

if (content.matches(".*[\\u0000-\\u001F]{5,}.*")) {
    isStuck = true
    stuckReason = "control_character_loop"
}
if (content.matches(".*(.)\\1{10,}.*")) {
    isStuck = true
    stuckReason = "character_repetition"
}
if (doneReason == "length" && content.replaceAll("[^a-zA-Z0-9 ]", "").length() < 5) {
    isStuck = true
    stuckReason = "length_limit_no_content"
}

// Mark sample as failed if stuck
if (isStuck) {
    prev.setSuccessful(false)
    prev.setResponseMessage("STUCK_INFERENCE: " + stuckReason)
}

// Store as JMeter variables
vars.put("ttft_ms",           String.format("%.2f", ttft_ms))
vars.put("tpot_ms",           String.format("%.2f", tpot_ms))
vars.put("tokens_per_second", String.format("%.2f", tokensPerSecond))
vars.put("inference_time_ms", String.format("%.2f", inferenceTime_ms))
vars.put("total_time_ms",     String.format("%.2f", totalTime_ms))
vars.put("eval_count",        String.valueOf(evalCount))
vars.put("prompt_eval_count", String.valueOf(promptEvalCount))
vars.put("is_stuck",          String.valueOf(isStuck))
vars.put("stuck_reason",      stuckReason)

// Log metrics
log.info("=== LLM METRICS ===")
log.info("TTFT (approx):    " + String.format("%.2f", ttft_ms)          + " ms")
log.info("TPOT:             " + String.format("%.2f", tpot_ms)          + " ms/token")
log.info("Tokens/sec:       " + String.format("%.2f", tokensPerSecond)  + " t/s")
log.info("Inference time:   " + String.format("%.2f", inferenceTime_ms) + " ms")
log.info("Total (Ollama):   " + String.format("%.2f", totalTime_ms)     + " ms")
log.info("Total (JMeter):   " + jmeterTime_ms                           + " ms")
log.info("Output tokens:    " + evalCount)
log.info("Input tokens:     " + promptEvalCount)
log.info("Stuck inference:  " + isStuck + " (" + stuckReason + ")")
log.info("===================")

// Write directly to InfluxDB from Groovy
def influxUrl = "http://localhost:8087/write?db=llm_perf"
def timestamp = System.currentTimeMillis() * 1000000 // nanoseconds

def lineProtocol = "llm_metrics," +
    "application=llm_test " +
    "ttft_ms=${ttft_ms}," +
    "tpot_ms=${tpot_ms}," +
    "tokens_per_second=${tokensPerSecond}," +
    "inference_time_ms=${inferenceTime_ms}," +
    "is_stuck=${isStuck ? 1 : 0} " +
    "${timestamp}"

def post = new URL(influxUrl).openConnection()
post.setRequestMethod("POST")
post.setDoOutput(true)
post.getOutputStream().write(lineProtocol.getBytes("UTF-8"))
post.getResponseCode()

log.info("Custom metrics pushed to InfluxDB")