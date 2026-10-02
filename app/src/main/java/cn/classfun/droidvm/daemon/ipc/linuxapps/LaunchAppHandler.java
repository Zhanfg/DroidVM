// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.daemon.ipc.linuxapps;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONArray;
import org.json.JSONObject;

import cn.classfun.droidvm.daemon.linuxapps.DroidBridgeSerialClient;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.linuxapps.DroidBridgeVmConfig;
import cn.classfun.droidvm.linuxapps.LinuxAppDescriptor;

@AutoService(RequestHandler.class)
public final class LaunchAppHandler extends RequestHandler {
    private static final int MAX_ITEMS = 64;
    private static final int MAX_ITEM_LENGTH = 64 * 1024;

    @NonNull
    @Override
    public String getName() {
        return "linux_app_launch";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        var appId = params.optString("app_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        if (!LinuxAppDescriptor.isValidAppId(appId))
            throw new RequestException("invalid app_id");

        var vm = request.getContext().getVMs().findById(vmId);
        if (vm == null)
            throw new RequestException("VM not found");
        if (!DroidBridgeVmConfig.isEnabled(vm))
            throw new RequestException("DroidBridge is not enabled for this VM");

        var payload = new JSONObject();
        payload.put("op", "apps.launch");
        payload.put("app_id", appId);
        payload.put("files", validatedArray(params.optJSONArray("files")));
        payload.put("uris", validatedArray(params.optJSONArray("uris")));

        var response = DroidBridgeSerialClient.request(
            vm, vm.item.optString(DroidBridgeVmConfig.STREAM_KEY, ""), payload);
        if (!response.optBoolean("ok", false))
            throw new RequestException(response.optString("message", "DroidBridge apps.launch failed"));

        request.res().put("launch_id", response.optString("launch_id", ""));
        request.res().put("pid", response.optLong("pid", -1));
    }

    @NonNull
    private static JSONArray validatedArray(JSONArray input) throws RequestException {
        var out = new JSONArray();
        if (input == null) return out;
        if (input.length() > MAX_ITEMS)
            throw new RequestException("too many files or URIs");
        for (int i = 0; i < input.length(); i++) {
            var value = input.optString(i, null);
            if (value == null || value.length() > MAX_ITEM_LENGTH)
                throw new RequestException("invalid file or URI argument");
            out.put(value);
        }
        return out;
    }
}
