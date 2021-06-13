import { Serializable, JsonProperty } from 'typescript-json-serializer';

// https://github.com/GillianPerard/typescript-json-serializer#readme

@Serializable()
export class Movie {

  @JsonProperty() title!: string;
  @JsonProperty() thumbnail!: String;
  @JsonProperty() id!: number;
}