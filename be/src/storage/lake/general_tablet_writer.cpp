// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "storage/lake/general_tablet_writer.h"

#include <fmt/format.h>

#include "column/chunk.h"
#include "common/config.h"
#include "fs/fs_util.h"
#include "serde/column_array_serde.h"
#include "storage/lake/filenames.h"
#include "storage/rowset/segment_writer.h"

namespace starrocks::lake {

GeneralTabletWriter::GeneralTabletWriter(Tablet tablet) : _tablet(tablet) {}

GeneralTabletWriter::~GeneralTabletWriter() = default;

// To developers: Do NOT perform any I/O in this method, because this method may be invoked
// in a bthread.
Status GeneralTabletWriter::open() {
    return Status::OK();
}

Status GeneralTabletWriter::write(const starrocks::Chunk& data) {
    if (_seg_writer == nullptr || _seg_writer->estimate_segment_size() >= config::max_segment_file_size ||
        _seg_writer->num_rows_written() + data.num_rows() >= INT32_MAX /*TODO: configurable*/) {
        RETURN_IF_ERROR(flush_segment_writer());
        RETURN_IF_ERROR(reset_segment_writer());
    }
    RETURN_IF_ERROR(_seg_writer->append_chunk(data));
    _num_rows += data.num_rows();
    return Status::OK();
}

Status GeneralTabletWriter::flush() {
    return flush_segment_writer();
}

Status GeneralTabletWriter::finish() {
    RETURN_IF_ERROR(flush_segment_writer());
    _finished = true;
    return Status::OK();
}

void GeneralTabletWriter::close() {
    if (!_finished && !_files.empty()) {
        // Delete files
        auto maybe_fs = FileSystem::CreateSharedFromString(_tablet.root_location());
        if (maybe_fs.ok()) {
            auto fs = std::move(maybe_fs).value();
            for (const auto& name : _files) {
                auto path = _tablet.segment_location(name);
                (void)fs->delete_file(path);
            }
        }
    }
    std::vector<std::string> tmp;
    std::swap(tmp, _files);
}

Status GeneralTabletWriter::reset_segment_writer() {
    if (_schema == nullptr) {
        ASSIGN_OR_RETURN(_schema, _tablet.get_schema());
    }
    auto name = random_segment_filename();
    ASSIGN_OR_RETURN(auto of, fs::new_writable_file(_tablet.segment_location(name)));
    SegmentWriterOptions opts;
    auto w = std::make_unique<SegmentWriter>(std::move(of), _seg_id++, _schema.get(), opts);
    RETURN_IF_ERROR(w->init());
    _seg_writer = std::move(w);
    _files.emplace_back(std::move(name));
    return Status::OK();
}

Status GeneralTabletWriter::flush_segment_writer() {
    if (_seg_writer != nullptr) {
        uint64_t segment_size = 0;
        uint64_t index_size = 0;
        uint64_t footer_position = 0;
        RETURN_IF_ERROR(_seg_writer->finalize(&segment_size, &index_size, &footer_position));
        _data_size += segment_size;
        _seg_writer.reset();
    }
    return Status::OK();
}

} // namespace starrocks::lake
