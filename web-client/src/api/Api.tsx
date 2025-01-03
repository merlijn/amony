import Cookies from "js-cookie";
import {buildUrl} from "./Util";
import axios from 'axios';
import {Constants} from "./Constants";

export type SessionInfo = {
  isLoggedIn: () => boolean
  isAdmin: () => boolean
}

type SessionData = {
  userId: string
  roles: string[]
}

export const Api = {

  fetchSession: async function FetchSession() {
    const response =  await doRequest('GET', "/api/auth/session")

    if (response.status === 200) {
      const sessionData = await parseResponseAsJson(response);
      const sessionInfo: SessionInfo = {
        isLoggedIn: ()    => true,
        isAdmin: ()       => sessionData.roles.includes("admin")
      }
      return sessionInfo;
    } else {
      return Constants.anonymousSession;
    }
  },

  refreshToken: async function() {
    return doRequest('POST', "/api/auth/refresh", false, null, false)
  },

  logout: async function Logout() {
    return doPOST("/api/auth/logout");
  },

  uploadFile: async function(file: File) {

    const formData = new FormData();

    formData.append(
      "video",
      file,
      file.name
    );

    axios.post("/api/resources/upload", formData);
  },

  updateThumbnailTimestamp: async function (mediaId: string, time: number) {
    return doPOST(`/api/resources/media/${mediaId}/update_thumbnail_timestamp`, { timestampInMillis: time })
  },

  adminReindexBucket: async function (bucketId: string) {
    return doPOST(buildUrl("/api/admin/reindex", new Map([["bucketId", bucketId]])))
  },

  adminRefreshBucket: async function (bucketId: string) {
    return doPOST(buildUrl("/api/admin/refresh", new Map([["bucketId", bucketId]])))
  }
}

const contentTypeHeader = { 'Content-type': 'application/json; charset=UTF-8' };

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

export async function doGET(path: string) {
  return await doRequest('GET', path).then(parseResponseAsJson)
}

export async function doDelete(path: string) {
  return await doRequest('DELETE', path)
}

export async function doPOST(path: string, postData?: any) {
  return await doRequest('POST', path, true, postData).then(parseResponseAsJson)
}

export async function doRequest(method: string,
                                path: string,
                                requireJson: boolean = true,
                                body?: any,
                                refreshTokenOn401: boolean = true): Promise<Response> {

  const init = body ?
    { method: method, body: JSON.stringify(body), headers: { ...commonHeaders(requireJson), ...contentTypeHeader } } :
    { method: method, headers: commonHeaders(requireJson) }

  const response: Response = await fetch(path, init);

  // Handle 401 Unauthorized
  if (response.status === 401 && refreshTokenOn401) {
    // Try to refresh token
    console.log("Refreshing token")
    const refreshResponse = await Api.refreshToken();
    if (refreshResponse.status === 200) {
      // Retry the original request
      return await doRequest(method, path, requireJson, body, false);
    }
  }

  return response;
}