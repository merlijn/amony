import React, {useEffect, useState} from "react";
import {useHistory} from "react-router-dom";
import {buildUrl} from "../api/Util";
import Navbar from "react-bootstrap/Navbar";
import Nav from "react-bootstrap/Nav";
import NavDropdown from "react-bootstrap/NavDropdown";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import Button from "react-bootstrap/Button";
import './TopNavBar.scss';
import GalleryPagination from "./GalleryPagination";

function TopNavBar(props: { current: number, last: number }) {

  const [query, setQuery] = useState("")
  const [collections, setCollections] = useState(["1", "2", "3"])

  const history = useHistory();

  const doSearch = (e: any) => {
    e.preventDefault();
    const target = buildUrl("/search", new Map( [["q", query]] ))
    history.push(target);
  };

  useEffect(() => {
    const target = buildUrl("/api/videos/collections", new Map())
  });

  const searchChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    console.log("App.searchChanged()");
    setQuery(e.target.value);
  };

  // fixed="top"

  return(
    <Navbar className="TopNavBar">
      {/*<Navbar.Toggle aria-controls="basic-navbar-nav"/>*/}
      {/*<Navbar.Collapse id="basic-navbar-nav">*/}
        <Nav>
          <div className="absolute-left">
            <NavDropdown title="Lists" id="basic-nav-dropdown">
              {
                collections.map((c) => {
                  return <NavDropdown.Item href="/collection/">{c}</NavDropdown.Item>
                })
              }
            </NavDropdown>
            <Nav.Link href="/">Home</Nav.Link>
          </div>
          <Form className="justify-content-center search-form" onSubmit={doSearch} inline>
            <FormControl id="search-input" className="mr-sm-2" size="sm" type="text" placeholder="Search" onChange={searchChanged} />
            <Button size="sm" variant="outline-success" onClick={doSearch}>Search</Button>
          </Form>
        </Nav>
        <GalleryPagination className="absolute-right" current={props.current} last={props.last} />
      {/*</Navbar.Collapse>*/}
    </Navbar>
  );
}

export default TopNavBar