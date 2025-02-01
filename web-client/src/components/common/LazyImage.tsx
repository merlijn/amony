import React from "react";
import { useInView } from "react-intersection-observer";

type LazyImageProps = {
  loadImage: () => React.ReactElement<React.ImgHTMLAttributes<HTMLImageElement>>;
}

const LazyImage =  (props: LazyImageProps ) => {

  const { ref, inView } = useInView({
    triggerOnce: true,
    rootMargin: "200px 0px",
  });

  return (
    <div ref   = { ref } >
      { inView ? props.loadImage() : null }
    </div>
  );
};

export default LazyImage;