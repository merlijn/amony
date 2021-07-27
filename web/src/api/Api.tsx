import {buildUrl, copyParams} from "./Util";

const headers = { 'Content-type': 'application/json; charset=UTF-8' };

export const Api = {

  getVideos: async function getVideos(q: string, tag: string | null, n: number, offset: number) {

    const apiParams = new Map([
      ["q", q],
      ["n", n.toString()],
      ["offset", offset.toString()]
    ]);

    if (tag)
      apiParams.set("c", tag)

    const target = buildUrl("/api/media", apiParams)

    return doGET(target)
  },

  getMediaById: async function (id: string) {
    return doGET(`/api/media/${id}`)
  },

  addFragment: async function (id: string, from: number, to: number) {
    return doPOST(`/api/thumbnail/${id}`, { from: from, to: to})
  },

  getTags: async function getTags() {
    return doGET('/api/tags')
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

export async function doPOST(path: string, postData: any) {

  const response = await fetch(path, {
    method: 'POST',
    body: JSON.stringify(postData),
    headers
  });

  const data = await response.json();

  if (data.error) {
    throw new Error(data.error);
  }

  return data;
}