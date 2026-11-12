// 실험 JVM을 PG 승인 어댑터 진입점에서 멈춰 외부 요청 전 종료 지점을 고정한다.
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.EventRequest;

public class BeforePgApproval {
    private static final String ADAPTER =
        "com.kitschcatch.backend.domain.order.toss.HttpTossPaymentsClient";
    private static final String SERVICE =
        "com.kitschcatch.backend.domain.order.service.PaymentService";

    public static void main(String[] args) throws Exception {
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst().orElseThrow();
        var options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1");
        options.get("port").setValue(args[0]);
        options.get("timeout").setValue("5000");
        var vm = connector.attach(options);
        var adapter = vm.classesByName(ADAPTER).stream().findFirst().orElseThrow();
        var method = adapter.methodsByName("confirm").stream().findFirst().orElseThrow();
        var request = vm.eventRequestManager().createBreakpointRequest(method.location());
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        request.enable();
        System.out.println("ARMED");
        try {
            while (true) {
                var events = vm.eventQueue().remove();
                for (var event : events) {
                    if (event instanceof BreakpointEvent entry) {
                        var caller = entry.thread().frame(1).location().method();
                        if (!caller.declaringType().name().equals(SERVICE)
                            || !caller.name().equals("confirmPayment")) {
                            throw new IllegalStateException("Unexpected approval caller: " + caller);
                        }
                        System.out.println("PAUSED " + entry.location() + " <- " + caller);
                        // 중단된 JVM을 재개하지 않고 실행기가 종료할 때까지 연결을 유지한다.
                        while (true) {
                            vm.eventQueue().remove();
                        }
                    }
                }
                events.resume();
            }
        } catch (VMDisconnectedException ignored) {
            // 실행기가 실험 JVM을 종료하면 디버거 연결도 함께 닫힌다.
        }
    }
}
