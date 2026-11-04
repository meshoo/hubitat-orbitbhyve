package groovyx.net.http
class HttpResponseException extends RuntimeException { def response; HttpResponseException(int s) { super("HTTP " + s); response = [status: s] } }
