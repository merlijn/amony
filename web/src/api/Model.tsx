export class Video {
  constructor(
    public title: string,
    public thumbnail: Thumbnail,
    public id: string,
    public resolution: string,
    public duration: number // in millis
  ) { }
}

export class SearchResult {

  constructor(
    public currentPage: number,
    public pageSize: number,
    public total: number,
    public videos: Video[]
  ) {
  }
}

export type Collection = {
  id: number,
  name: string
}

export type Thumbnail = {
  timestamp: number,
  uri: string
}