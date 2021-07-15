import React, {useEffect, useRef, useState} from "react";
import {useHistory, useLocation} from "react-router-dom";
import {buildUrl, copyParams} from "../api/Util";
import Navbar from "react-bootstrap/Navbar";
import Nav from "react-bootstrap/Nav";
import NavDropdown from "react-bootstrap/NavDropdown";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import Button from "react-bootstrap/Button";
import './TopNavBar.scss';
import GalleryPagination from "./GalleryPagination";
import {doGET} from "../api/Api";
import {Collection} from "../api/Model";

function TopNavBar(props: { currentPage: number, lastPage: number }) {

  const [query, setQuery] = useState("")

  const collections = useRef<Array<Collection>>([]);

  const history = useHistory();
  const loc = useLocation();

  const params = copyParams(new URLSearchParams(loc.search))

  const doSearch = (e: any) => {
    e.preventDefault();
    setQuery("")
    const target = buildUrl("/search", new Map( [["q", query]] ))
    history.push(target);
  };

  useEffect(() => {
    const target = buildUrl("/api/collections", new Map())
    console.log("render:" + target)

    doGET(target).then(response => { collections.current = response; });
  }, [props]);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  // fixed="top"
  return(
    <Navbar className="TopNavBar">
        <Nav>
          <div className="absolute-left">
            <NavDropdown title="Lists" id="basic-nav-dropdown">
              {
                collections.current.map((c) => {

                  const link = buildUrl("/search", params.set("c", c.id.toString()))
                  return <NavDropdown.Item href={link}>{c.title}</NavDropdown.Item>
                })
              }
            </NavDropdown>
            <Nav.Link href="/">Home</Nav.Link>
          </div>
          <Form className="justify-content-center search-form" onSubmit={doSearch} inline>
            <FormControl id="search-input" className="mr-sm-2" size="sm" type="text" placeholder="Search" value={query} onChange={queryChanged} />
            <Button size="sm" variant="outline-success" onClick={doSearch}>Search</Button>
          </Form>
        </Nav>
        <GalleryPagination className="absolute-right" current={props.currentPage} last={props.lastPage} />
    </Navbar>
  );
}

export default TopNavBar