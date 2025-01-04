import Cookies from "js-cookie";

export type SessionInfo = {
  isLoggedIn: () => boolean
  isAdmin: () => boolean
}

export const Api = {

  logout: async function Logout() {
    return doPOST("/api/auth/logout");
  },
}

function commonHeaders(requireJson: boolean = true): {} {
  const xsrfTokenCookie = Cookies.get("XSRF-TOKEN");
  const xXsrfToken = xsrfTokenCookie ?  { 'X-XSRF-TOKEN': xsrfTokenCookie } : { };
  const acceptHeader = requireJson ? { 'Accept': 'application/json; charset=UTF-8' } : { };

  return { ...acceptHeader, ...xXsrfToken }
}

async function parseResponseAsJson(response: Response) {
  const data =  await response.text();
  return data ? JSON.parse(data) : undefined;
}

export async function doPOST(path: string, postData?: any) {
  return await doRequest('POST', path, true, postData).then(parseResponseAsJson)
}

export async function doRequest(method: string,
                                path: string,
                                requireJson: boolean = true): Promise<Response> {

  const init = { method: method, headers: commonHeaders(requireJson) }

  const response: Response = await fetch(path, init);

  return response;
}