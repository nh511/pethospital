import { describe, expect, it, vi } from "vitest";
import { createWechatGateway, type WechatApi } from "../platform/wechat";

describe("微信API替身", () => {
  it("只把临时code交给调用方，不持有AppSecret", async () => {
    const login = vi.fn<WechatApi["login"]>((options: Parameters<WechatApi["login"]>[0]) =>
      options.success({ code: " temporary-code " }));
    const api = { login, request: vi.fn<WechatApi["request"]>() } as WechatApi;

    await expect(createWechatGateway(api).loginCode()).resolves.toBe("temporary-code");
    expect(login).toHaveBeenCalledOnce();
    expect(JSON.stringify(api)).not.toContain("AppSecret");
  });

  it("空code和微信失败均明确拒绝", async () => {
    const emptyApi = {
      login: (options) => options.success({ code: " " }),
      request: vi.fn<WechatApi["request"]>(),
    } satisfies WechatApi;
    await expect(createWechatGateway(emptyApi).loginCode()).rejects.toThrow("未返回code");

    const failure = new Error("login unavailable");
    const failedApi = {
      login: (options) => options.fail(failure),
      request: vi.fn<WechatApi["request"]>(),
    } satisfies WechatApi;
    await expect(createWechatGateway(failedApi).loginCode()).rejects.toBe(failure);
  });

  it("请求替身保留方法、数据和响应状态", async () => {
    let requestCount = 0;
    const request: WechatApi["request"] = <T>(options: {
      url: string;
      method: "GET" | "POST";
      data?: unknown;
      success(result: { statusCode: number; data: T }): void;
      fail(error: unknown): void;
    }) => {
      requestCount += 1;
      options.success({
        statusCode: 200,
        data: { code: "OK" } as T,
      });
    };
    const api = { login: vi.fn<WechatApi["login"]>(), request } as WechatApi;

    await expect(createWechatGateway(api).request<{ code: string }>(
      "http://127.0.0.1:8080/api/v1/hospital",
      "GET",
    )).resolves.toEqual({ statusCode: 200, data: { code: "OK" } });
    expect(requestCount).toBe(1);
  });
});
