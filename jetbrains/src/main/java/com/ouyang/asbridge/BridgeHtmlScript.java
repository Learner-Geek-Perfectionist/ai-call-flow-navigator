package com.ouyang.asbridge;

final class BridgeHtmlScript {
    private BridgeHtmlScript() {
    }

    static String content() {
        return """
                (() => {
                  const scriptUrl = document.currentScript ? document.currentScript.src : "";
                  const bridgeOrigin = scriptUrl ? new URL(scriptUrl, document.baseURI).origin : "http://127.0.0.1:17321";

                  const getData = element => {
                    return {
                      asPath: element.dataset ? element.dataset.asPath : element.getAttribute("data-as-path"),
                      asLine: element.dataset ? element.dataset.asLine : element.getAttribute("data-as-line"),
                      asColumn: element.dataset
                        ? (element.dataset.asColumn || element.dataset.asCol)
                        : (element.getAttribute("data-as-column") || element.getAttribute("data-as-col"))
                    };
                  };

                  const hasTarget = value => {
                    return value instanceof Element && Boolean(getData(value).asPath);
                  };

                  const findTarget = event => {
                    const path = typeof event.composedPath === "function" ? event.composedPath() : [];
                    for (const value of path) {
                      if (hasTarget(value)) {
                        return value;
                      }
                    }

                    let current = event.target;
                    while (current instanceof Element) {
                      if (hasTarget(current)) {
                        return current;
                      }
                      current = current.parentElement || current.parentNode;
                    }
                    return null;
                  };

                  const toBridgeRequest = element => {
                    const { asPath, asLine, asColumn } = getData(element);
                    if (!asPath) {
                      return "";
                    }

                    const line = asLine || "1";
                    const column = asColumn || "1";
                    return bridgeOrigin
                      + "/open?path=" + encodeURIComponent(asPath)
                      + "&line=" + encodeURIComponent(line)
                      + "&column=" + encodeURIComponent(column);
                  };

                  const shouldHandleEvent = event => {
                    if (event.type === "click") {
                      return event.button === 0;
                    }
                    if (event.type === "auxclick") {
                      return event.button === 1;
                    }
                    if (event.type === "keydown") {
                      return event.key === "Enter" || event.key === " ";
                    }
                    return false;
                  };

                  const openQuietly = requestUrl => {
                    if (navigator.sendBeacon && navigator.sendBeacon(requestUrl, "")) {
                      return;
                    }
                    fetch(requestUrl, {
                      method: "POST",
                      mode: "cors",
                      credentials: "omit",
                      keepalive: true
                    }).catch(() => {});
                  };

                  const handleActivation = event => {
                    if (!shouldHandleEvent(event)) {
                      return;
                    }

                    const target = findTarget(event);
                    const requestUrl = target ? toBridgeRequest(target) : "";
                    if (!requestUrl) {
                      return;
                    }

                    event.preventDefault();
                    event.stopImmediatePropagation();
                    openQuietly(requestUrl);
                  };

                  document.addEventListener("click", handleActivation, true);
                  document.addEventListener("auxclick", handleActivation, true);
                  document.addEventListener("keydown", handleActivation, true);
                })();
                """;
    }
}
