// 복구 워커를 결과 저장 직전에 멈추고 재개 후 오래된 lease의 거절을 관측한다.
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.EventRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class BeforeRecoverySave {
    private static final String SERVICE = "com.kitschcatch.backend.domain.order.service.PaymentRecoveryService";
    private static final String SCHEDULER = "com.kitschcatch.backend.domain.order.service.PaymentRecoveryScheduler";

    public static void main(String[] args) throws Exception {
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst().orElseThrow();
        var options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1");
        options.get("port").setValue(args[0]);
        options.get("timeout").setValue("5000");
        var vm = connector.attach(options);
        var service = vm.classesByName(SERVICE).stream().findFirst().orElseThrow();
        var method = service.methodsByName(args[1]).stream()
            .filter(candidate -> candidate.argumentTypeNames().size() == 3)
            .findFirst().orElseThrow();
        var manager = vm.eventRequestManager();
        var entry = manager.createBreakpointRequest(method.location());
        var guard = manager.createBreakpointRequest(method.locationsOfLine(Integer.parseInt(args[2])).getFirst());
        entry.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        guard.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        entry.enable();
        guard.enable();
        System.out.println("ARMED");
        var input = new BufferedReader(new InputStreamReader(System.in));
        try {
            while (true) {
                var events = vm.eventQueue().remove();
                for (var event : events) {
                    if (!(event instanceof BreakpointEvent hit)) continue;
                    var thread = hit.thread();
                    if (thread.frames().stream().noneMatch(frame ->
                            frame.location().declaringType().name().equals(SCHEDULER)
                                && frame.location().method().name().equals("recoverPayments"))) {
                        continue;
                    }
                    var values = thread.frame(0).getArgumentValues();
                    var attemptId = ((StringReference) values.get(0)).value();
                    var token = ((StringReference) values.get(2)).value();
                    if (hit.request().equals(entry)) {
                        String result;
                        if (values.get(1) instanceof ObjectReference body
                                && !(body instanceof StringReference)) {
                            result = ((StringReference) body.getValue(body.referenceType().fieldByName("status"))).value();
                        } else {
                            result = ((StringReference) values.get(1)).value();
                        }
                        System.out.println("PAUSED " + attemptId + " " + token + " " + result);
                        if (!"resume".equals(input.readLine())) return;
                        entry.disable();
                    } else if (hit.request().equals(guard)) {
                        System.out.println("GUARD " + attemptId + " " + token + " " + hit.location());
                        guard.disable();
                    }
                }
                events.resume();
            }
        } catch (VMDisconnectedException ignored) {
            // 실험 JVM 종료와 함께 디버거 연결을 닫는다.
        }
    }
}
