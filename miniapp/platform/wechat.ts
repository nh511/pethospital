export interface LoginResult {
  code: string;
}

export interface RequestResult<T> {
  statusCode: number;
  data: T;
}

export interface WechatApi {
  login(options: {
    success(result: LoginResult): void;
    fail(error: unknown): void;
  }): void;
  request<T>(options: {
    url: string;
    method: "GET" | "POST";
    data?: unknown;
    success(result: RequestResult<T>): void;
    fail(error: unknown): void;
  }): void;
}

export function createWechatGateway(api: WechatApi) {
  return {
    loginCode(): Promise<string> {
      return new Promise((resolve, reject) => api.login({
        success(result) {
          const code = result.code.trim();
          if (!code) {
            reject(new Error("微信登录未返回code"));
            return;
          }
          resolve(code);
        },
        fail: reject,
      }));
    },
    request<T>(url: string, method: "GET" | "POST", data?: unknown): Promise<RequestResult<T>> {
      return new Promise((resolve, reject) => api.request<T>({
        url,
        method,
        data,
        success: resolve,
        fail: reject,
      }));
    },
  };
}
