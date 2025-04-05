import {DetailedHTMLProps, ImgHTMLAttributes} from "react";
import {Constants} from "../../api/Constants";

const ImgWithAlt = (props: DetailedHTMLProps<ImgHTMLAttributes<HTMLImageElement>, HTMLImageElement>) => {

  return <img {  ...props } alt = { Constants.imgAlt } />
}

export default ImgWithAlt