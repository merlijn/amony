import {buildUrl} from "./Util";
import {Sort, SortDirection, VideoMeta} from "./Model";

const headers = { 'Content-type': 'application/json; charset=UTF-8' };

export const Api = {

  getVideos: async function getVideos(q: string, n: number,
                                      offset: number, 
                                      dir?: string, 
                                      minRes?: number, 
                                      sort?: Sort) {

    const apiParams = new Map([
      ["q", q],
      ["n", n.toString()],
      ["offset", offset.toString()]
    ]);

    if (dir)
      apiParams.set("dir", dir)
    if (minRes)
      apiParams.set("min_res", minRes.toString())
    if (sort) {
      apiParams.set("sort_field", sort.field)
      apiParams.set("sort_dir", sort.direction)
    }

    const target = buildUrl("/api/search", apiParams)

    return doGET(target)
  },

  getMediaById: async function (id: string) {
    return doGET(`/api/media/${id}`)
  },

  getTags: async function () {
    return doGET(`/api/tags`)
  },

  updateVideoMetaData: async function(id: string, meta: VideoMeta) {
    return doPOST(`/api/media/${id}`, meta)
  },

  deleteMediaById: async function (id: string) {
    return doDelete(`/api/media/${id}`)
  },

  updateFragmentTags: async function (id: string, idx: number, tags: Array<string>) {
    return doPOST(`/api/fragments/${id}/${idx}/tags`, tags)
  },

  addFragment: async function (id: string, from: number, to: number) {
    return doPOST(`/api/fragments/${id}/add`, { from: from, to: to})
  },

  deleteFragment: async function (id: string, idx: number) {
    return doDelete(`/api/fragments/${id}/${Math.trunc(idx)}`)
  },

  updateFragment: async function (id: string, idx: number, from: number, to: number) {
    return doPOST(`/api/fragments/${id}/${Math.trunc(idx)}`, { from: from, to: to})
  },

  getDirectories: async function getTags() {
    return doGET('/api/directories')
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

  const data = await response.json();

  if (data.error) {
    throw new Error(data.error);
  }

  return data;
}