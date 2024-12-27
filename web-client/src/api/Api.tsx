import Cookies from "js-cookie";
import {ResourceSelection, Sort, ResourceUserMeta, Resource} from "./Model";
import { buildUrl } from "./Util";
import axios from 'axios';
import {Constants, durationAsParam} from "./Constants";

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

  login: async function(username: string, password: string) {

    return doPOST("/api/auth/login", { username: username, password: password})
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

  getFragments: async function(n: number, offset: number, tag?: string) {

    const params = new Map([
      ["n", n.toString()],
      ["offset", offset.toString()]
    ]);

    if (tag)
      params.set("tags", tag)

    const url = buildUrl("/api/search/fragments", params)

    return doGET(url)
  },

  searchMedia: async function(n: number, offset: number, selection: ResourceSelection) {
    return Api.getMedias(
              selection.query || "",
              n,
              offset,
              selection.tag,
              selection.playlist,
              selection.minimumQuality,
              selection.duration,
              selection.sort)
  },

  // TOOD remove
  getMedias: async function(
      q: string, 
      n: number,
      offset: number, 
      tag?: string,
      playlist?: string, 
      minRes?: number, 
      duration?: [number?, number?],
      sort?: Sort) {

    const apiParams = new Map([
      ["q", q],
      ["n", n.toString()],
      ["offset", offset.toString()]
    ]);

    if (tag)
      apiParams.set("tags", tag)
    if (playlist)
      apiParams.set("playlist", playlist)
    if (minRes)
      apiParams.set("min_res", minRes.toString())
    if (sort) {
      apiParams.set("sort_field", sort.field)
      apiParams.set("sort_dir", sort.direction)
    }
    if (duration) {
      apiParams.set("d", durationAsParam([duration[0] ? duration[0] * 1000 : duration[0], duration[1] ? duration[1] * 1000 : duration[1]]))
    }

    const target = buildUrl("/api/search/media", apiParams)

    return doGET(target)
  },

  getResourceById: async function (resourceId: string) {
    return doGET(`/api/resources/media/${resourceId}`).then((response) => { return response as Resource })
  },

  updateUserMetaData: async function(bucketId: String, resourceId: string, meta: ResourceUserMeta) {
    return doPOST(`/api/resources/${bucketId}/${resourceId}/update_user_meta`, meta)
  },

  deleteResourceById: async function (bucketId: String, resourceId: string) {
    return doDelete(`/api/resources/${resourceId}`)
  },

  updateFragmentTags: async function (mediaId: string, idx: number, tags: Array<string>) {
    return doPOST(`/api/fragments/${mediaId}/${idx}/tags`, tags)
  },

  addFragment: async function (mediaId: string, from: number, to: number) {
    return doPOST(`/api/fragments/${mediaId}/add`, { from: from, to: to})
  },

  deleteFragment: async function (mediaId: string, idx: number) {
    return doDelete(`/api/fragments/${mediaId}/${Math.trunc(idx)}`)
  },

  updateFragment: async function (mediaId: string, idx: number, from: number, to: number) {
    return doPOST(`/api/fragments/${mediaId}/${Math.trunc(idx)}`, { from: from, to: to})
  },

  updateThumbnailTimestamp: async function (mediaId: string, time: number) {
    return doPOST(`/api/resources/media/${mediaId}/update_thumbnail_timestamp`, { timestampInMillis: time })
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