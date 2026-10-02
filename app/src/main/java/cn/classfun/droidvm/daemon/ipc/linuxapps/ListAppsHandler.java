// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.daemon.ipc.linuxapps;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONObject;

import cn.classfun.droidvm.daemon.linuxapps.DroidBridgeSerialClient;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.linuxapps.DroidBridgeVmConfig;

@AutoService(RequestHandler.class)
public final class ListAppsHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "linux_apps_list";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var vmId = request.getParams().optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");

        var vm = request.getContext().getVMs().findById(vmId);
        if (vm == null)
            throw new RequestException("VM not found");
        if (!DroidBridgeVmConfig.isEnabled(vm))
            throw new RequestException("DroidBridge is not enabled for this VM");

        var payload = new JSONObject();
        payload.put("op", "apps.list");
        var response = DroidBridgeSerialClient.request(
            vm, vm.item.optString(DroidBridgeVmConfig.STREAM_KEY, ""), payload);
        if (!response.optBoolean("ok", false))
            throw new RequestException(response.optString("message", "DroidBridge apps.list failed"));

        request.res().put("apps", response.optJSONArray("apps"));
    }
}
