import { Serializable, JsonProperty } from 'typescript-json-serializer';

// https://github.com/GillianPerard/typescript-json-serializer#readme

@Serializable()
export class Video {
  constructor(
      @JsonProperty()
      public title: string,
      @JsonProperty()
      public thumbnail: string,
      @JsonProperty()
      public id: string
  ) {}
}