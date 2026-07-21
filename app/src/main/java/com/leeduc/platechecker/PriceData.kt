package com.leeduc.platechecker

data class TollRow(
    val stationName: String,
    val loai1: String,
    val loai2: String,
    val loai3: String,
    val loai4: String,
    val loai5: String
)

data class TollSection(
    val title: String,
    val rows: List<TollRow>
)

object PriceData {

    val sections: List<TollSection> = listOf(
        TollSection(
            title = "BẢNG GIÁ THEO LOẠI XE",
            rows = listOf(
                TollRow("QL8A (Hồng Lĩnh, km479)", "161.000", "234.000", "315.000", "393.000", "627.000"),
                TollRow("ĐT548 (Đồng Lộc)", "147.000", "213.520", "287.666", "359.000", "572.000"),
                TollRow("ĐT550 (Thạch Hà - TP Hà Tĩnh)", "132.000", "193.000", "261.000", "325.000", "517.000"),
                TollRow("Cẩm Quan", "117.000", "169.000", "228.000", "285.000", "453.000"),
                TollRow("Kỳ Trung", "92.000", "131.000", "178.000", "222.000", "352.000"),
                TollRow("QL12C (Kỳ Anh)", "80.000", "114.000", "155.000", "193.000", "307.000"),
                TollRow("Tiến Châu Vân Hóa", "52.000", "73.000", "100.000", "125.000", "198.000"),
                TollRow("12A (Ba Đồn)", "45.000", "63.000", "87.000", "108.000", "172.000"),
                TollRow("Cự Nẫm", "26.000", "35.000", "50.000", "62.000", "99.000"),
                TollRow("Nông Trường", "10.000", "14.000", "19.000", "24.000", "38.000")
            )
        ),
        TollSection(
            title = "NHẬT LỆ",
            rows = listOf(
                TollRow("9B (Vạn Ninh)", "18.000", "27.000", "35.000", "44.000", "70.000"),
                TollRow("ĐT75 (Cam Lộ)", "30.000", "45.000", "59.000", "74.000", "118.000"),
                TollRow("9D (Vĩnh Linh - Bến Quan)", "43.000", "64.000", "85.000", "106.000", "170.000"),
                TollRow("9C (Kim Thủy - Kiến Giang)", "69.000", "94.000", "126.000", "157.000", "251.000"),
                TollRow("9A (Đông Hà, 740)", "78.000", "116.000", "154.000", "192.000", "307.000")
            )
        )
    )
}
