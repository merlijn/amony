import Cookies from "js-cookie";
import { ResourceSelection, Sort, ResourceUserMeta } from "./Model";
import { buildUrl } from "./Util";
import { JwtPayload, jwtDecode } from "jwt-decode";
import axios from 'axios';
import { durationAsParam } from "./Constants";

const headers = { 'Content-type': 'application/json; charset=UTF-8' };

export type Session = {
  isLoggedIn: () => boolean
  isAdmin: () => boolean
  hasRole: (role: string) => boolean
}

let jwtToken: any = undefined

const anonymousSession: Session = {
  isLoggedIn: () => false,
  isAdmin: () => false,
  hasRole: (s: string) => false
}

const adminSession: Session = {
  isLoggedIn: () => true,
  isAdmin: () => true,
  hasRole: (s: string) => true
}

const sessionFromToken = (token: any): Session => {
  return {
    isLoggedIn: () => true,
    isAdmin: () => jwtToken["admin"] as boolean,
    hasRole: (role: string) => true
  }
}

export const Api = {
  
  session: function Session(): Session {

    if (jwtToken) {
      return sessionFromToken(jwtToken);
    }

    const sessionCookie = Cookies.get("session");
    if (!jwtToken && sessionCookie) {
      const decoded = jwtDecode<JwtPayload>(sessionCookie);
      jwtToken = decoded;
      return sessionFromToken(jwtToken);
    }

    return adminSession // anonymousSession; TODO implement users / privileges
  },

  login: async function(username: string, password: string) {

    return doPOST("/api/identity/login", { username: username, password: password})
  },

  logout: async function Logout() {
    return doPOST("/api/identity/logout").then(() => {

      console.log("Logout completed, resetting jwt token")
      jwtToken = ""
    });
  },

  uploadFile: async function(file: File) {

    const formData = new FormData();

    formData.append(
      "video",
      file,
      file.name
    );

    axios.post("/resources/upload", formData);
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

  getMediaById: async function (mediaId: string) {
    return doGET(`/api/resources/media/${mediaId}`)
  },

  updateUserMetaData: async function(bucketId: String, resourceId: string, meta: ResourceUserMeta) {
    return doPOST(`/api/resources/${bucketId}/${resourceId}/update_user_meta`, meta)
  },

  deleteResourceById: async function (bucketId: String, mediaId: string) {
    return doDelete(`/api/media/${mediaId}`)
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

export async function doGET(path: string) {
  const headers = { 'Content-type': 'application/json; charset=UTF-8' };

  const response = await fetch(path, {
    method: 'GET',
    headers
  });

  const data = await response.json();

  if (data.error) {
    throw new Error(data.error);
  }

  return data;
}

export async function doDelete(path: string) {

  const response = await fetch(path, { method: 'DELETE', headers});
  const jsonBody = await response.json();

  if (jsonBody.error) {
    throw new Error(jsonBody.error);
  }

  return jsonBody;
}

export async function doPOST(path: string, postData?: any) {

  let init: {} = {
    method: 'POST',
    headers
  }

  if (postData)
    init = { ...init, body: JSON.stringify(postData) }

  const response = await fetch(path, init);

  const data = await response.text();

  return data ? JSON.parse(data) : undefined;
}