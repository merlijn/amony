import { Serializable, JsonProperty } from 'typescript-json-serializer';

// https://github.com/GillianPerard/typescript-json-serializer#readme

@Serializable()
export class Video {
  constructor(
      public title: string,
      public thumbnail: string,
      public id: string
  ) {}
}