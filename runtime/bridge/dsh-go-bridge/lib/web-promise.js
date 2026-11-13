// Runs in the page, before the Host's injected scripts and browser bundles.
// Checking the Host's Node global would miss an older Android WebView.
function installPromiseWithResolvers() {
  if (typeof Promise.withResolvers === "function") return;
  Object.defineProperty(Promise, "withResolvers", {
    configurable: true,
    enumerable: false,
    writable: true,
    value: {
      withResolvers() {
        let resolve;
        let reject;
        const promise = new this((onResolve, onReject) => {
          if (resolve !== undefined || reject !== undefined) {
            throw new TypeError("Promise capability already initialized");
          }
          resolve = onResolve;
          reject = onReject;
        });
        if (typeof resolve !== "function" || typeof reject !== "function") {
          throw new TypeError("Promise capability requires callable resolvers");
        }
        return { promise, resolve, reject };
      },
    }.withResolvers,
  });
}

const bootstrap = `<script>(${installPromiseWithResolvers.toString()})();</script>`;

export function injectPromiseWithResolvers(html) {
  return html.replace(/<head(?:\s[^>]*)?>/i, open => `${open}${bootstrap}`);
}
