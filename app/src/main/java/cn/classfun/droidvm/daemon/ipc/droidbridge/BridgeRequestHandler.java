// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.daemon.ipc.droidbridge;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONObject;

import java.util.Set;

import cn.classfun.droidvm.daemon.droidbridge.DroidBridgeTransport;
import cn.classfun.droidvm.daemon.droidbridge.DroidBridgeSerialTransport;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.vm.DroidBridgeConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * Narrow authenticated proxy from the Android app to the guest DroidBridge agent.
 */
@AutoService(RequestHandler.class)
public final class BridgeRequestHandler extends RequestHandler {
    private static final Set<String> ALLOWED_OPS =
        Set.of("hello", "apps.list", "apps.launch");

    @NonNull
    @Override
    public String getName() {
        return "droidbridge_request";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty()) throw new RequestException("missing vm_id");

        var payload = params.optJSONObject("request");
        if (payload == null) throw new RequestException("missing request");

        var op = payload.optString("op", "");
        if (!ALLOWED_OPS.contains(op))
            throw new RequestException(fmt("DroidBridge operation is not allowed: %s", op));

        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null) throw new RequestException(fmt("VM not found: %s", vmId));
        if (inst.getState() != VMState.RUNNING)
            throw new RequestException(fmt("VM is not running: %s", vmId));
        if (!DroidBridgeConfig.isEnabled(inst.item))
            throw new RequestException("DroidBridge is not enabled for this VM");

        // Use a copy so the outer IPC request remains immutable to handlers/logging.
        var bridgeRequest = new JSONObject(payload.toString());
        if (!bridgeRequest.has("id"))
            bridgeRequest.put("id", request.getId().toString());

        JSONObject bridgeResponse;
        if (DroidBridgeConfig.hostVsockAvailable()) {
            long cidLong = DroidBridgeConfig.cidFor(inst.getId());
            if (cidLong > Integer.MAX_VALUE)
                throw new RequestException("DroidBridge CID is out of host range");
            try {
                bridgeResponse = DroidBridgeTransport.request(
                    (int) cidLong, DroidBridgeConfig.AGENT_PORT, bridgeRequest);
            } catch (java.io.IOException e) {
                throw new RequestException("DroidBridge agent is unavailable");
            }
            request.res().put("transport", "vsock");
            request.res().put("vsock_cid", cidLong);
        } else if (DroidBridgeConfig.isSerialFallbackActive(inst.item)) {
            try {
                bridgeResponse = DroidBridgeSerialTransport.request(inst, bridgeRequest);
            } catch (Exception e) {
                throw new RequestException("DroidBridge serial agent is unavailable");
            }
            request.res().put("transport", "serial");
        } else {
            throw new RequestException(
                "No DroidBridge transport is available; Serial 4 is already in use");
        }

        request.res().put("bridge_response", bridgeResponse);
    }
}
